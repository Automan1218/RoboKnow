package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.DocumentVector;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.parent-chunk-size:0}")
    private int parentChunkSize;

    @Value("${file.parsing.chunk-overlap-ratio:0.1}")
    private double chunkOverlapRatio;

    @Value("${file.parsing.chunk-overlap-size:0}")
    private int chunkOverlapSize;

    @Value("${file.parsing.parent-child-enabled:true}")
    private boolean parentChildEnabled;

    @Value("${file.parsing.child-chunk-size:256}")
    private int childChunkSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    @Value("${ocr.api.url:http://localhost:8000}")
    private String ocrApiUrl;

    @Value("${ocr.api.min-text-length:100}")
    private int ocrMinTextLength;

    @Value("${ocr.api.timeout-seconds:120}")
    private int ocrTimeoutSeconds;

    private WebClient ocrWebClient;

    public ParseService() {
    }

    @PostConstruct
    public void initOcrClient() {
        this.ocrWebClient = WebClient.builder()
                .baseUrl(ocrApiUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    @Transactional
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("Start parsing file: fileMd5={}, userId={}, orgTag={}, isPublic={}",
                fileMd5, userId, orgTag, isPublic);

        checkMemoryThreshold();

        byte[] fileBytes = fileStream.readAllBytes();
        String text = extractText(fileBytes, fileMd5);

        if (text == null || text.isBlank()) {
            logger.warn("File content is empty; skipping parse: fileMd5={}", fileMd5);
            return;
        }

        // Kafka 消息可能被重复投递（重平衡、重试）。先清掉该文件已有的分块再写入，
        // 保证同一条消息处理多少次结果都一样，MySQL 里不会累积重复分块。
        documentVectorRepository.deleteByFileMd5(fileMd5);

        if (!parentChildEnabled) {
            List<String> chunks = splitTextIntoChunksWithSemantics(text, chunkSize);
            saveChildChunks(fileMd5, chunks, userId, orgTag, isPublic, 0);
            logger.info("Parsed file with flat chunks: fileMd5={}, chunks={}", fileMd5, chunks.size());
            return;
        }

        List<String> parentTexts = splitTextIntoChunksWithSemantics(text, resolvedParentChunkSize());
        List<Long> parentIds = saveParentChunks(fileMd5, parentTexts, userId, orgTag, isPublic);

        int childIdx = 0;
        for (int i = 0; i < parentTexts.size(); i++) {
            List<String> children = splitTextIntoChunksWithSemantics(parentTexts.get(i), chunkSize);
            childIdx = saveChildChunks(fileMd5, children, userId, orgTag, isPublic, childIdx, parentIds.get(i));
        }

        logger.info("Parsed file with parent-child chunks: fileMd5={}, parents={}, children={}",
                fileMd5, parentTexts.size(), childIdx);
    }

    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    private String extractText(byte[] fileBytes, String fileMd5) {
        String tikaText = extractWithTika(fileBytes, fileMd5);

        if (tikaText.trim().length() < ocrMinTextLength && isPdf(fileBytes)) {
            logger.info("Tika extracted only {} chars; trying OCR: fileMd5={}", tikaText.trim().length(), fileMd5);
            String ocrText = callPaddleOcr(fileBytes, fileMd5);
            if (ocrText != null && !ocrText.isBlank()) {
                logger.info("OCR extraction succeeded: chars={}", ocrText.length());
                return ocrText;
            }
            logger.warn("OCR extraction failed; using Tika result: fileMd5={}", fileMd5);
        }

        return tikaText;
    }

    private String extractWithTika(byte[] fileBytes, String fileMd5) {
        try {
            StringBuilder sb = new StringBuilder();
            StructureAwareContentHandler handler = new StructureAwareContentHandler(sb);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            try (InputStream is = new ByteArrayInputStream(fileBytes)) {
                parser.parse(is, handler, metadata, context);
            }

            logger.debug("Tika extracted chars={}, fileMd5={}", sb.length(), fileMd5);
            return sb.toString();
        } catch (SAXException e) {
            logger.error("Tika SAX parse failed: fileMd5={}", fileMd5, e);
            return "";
        } catch (Exception e) {
            logger.error("Tika parse failed: fileMd5={}", fileMd5, e);
            return "";
        }
    }

    private boolean isPdf(byte[] fileBytes) {
        return fileBytes.length >= 4
                && fileBytes[0] == 0x25
                && fileBytes[1] == 0x50
                && fileBytes[2] == 0x44
                && fileBytes[3] == 0x46;
    }

    private String callPaddleOcr(byte[] fileBytes, String fileMd5) {
        try {
            WebClient client = ocrWebClient != null ? ocrWebClient : WebClient.builder()
                    .baseUrl(ocrApiUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                    .build();
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return "document.pdf";
                }
            }).contentType(MediaType.APPLICATION_PDF);

            Map<?, ?> response = client.post()
                    .uri("/ocr")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(ocrTimeoutSeconds))
                    .block();

            if (response != null && response.get("text") instanceof String text) {
                return text;
            }
            return "";
        } catch (Exception e) {
            logger.error("OCR call failed: fileMd5={}", fileMd5, e);
            return "";
        }
    }

    private List<Long> saveParentChunks(String fileMd5, List<String> parentTexts,
            String userId, String orgTag, boolean isPublic) {
        List<Long> ids = new ArrayList<>();
        int parentIndex = 0;
        for (String text : parentTexts) {
            parentIndex++;
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(parentIndex);
            vector.setTextContent(text);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            vector.setParent(true);
            vector.setParentChunkId((Long) null);
            ids.add(documentVectorRepository.save(vector).getVectorId());
        }
        logger.info("Saved parent chunks: count={}", parentTexts.size());
        return ids;
    }

    private int saveChildChunks(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic, int startingChunkId) {
        return saveChildChunks(fileMd5, chunks, userId, orgTag, isPublic, startingChunkId, null);
    }

    private int saveChildChunks(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic, int startingChunkId, Long parentId) {
        int currentChunkId = startingChunkId;
        for (String chunk : chunks) {
            currentChunkId++;
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            vector.setParent(false);
            vector.setParentChunkId(parentId);
            documentVectorRepository.save(vector);
        }
        logger.info("Saved child chunks: count={}", chunks.size());
        return currentChunkId;
    }

    static final class ParentChildChunk {
        final String childContent;
        final int parentChunkId;
        final String parentContent;

        ParentChildChunk(String childContent, int parentChunkId, String parentContent) {
            this.childContent = childContent;
            this.parentChunkId = parentChunkId;
            this.parentContent = parentContent;
        }
    }

    List<ParentChildChunk> splitIntoParentChildChunks(String text) {
        List<String> parents = splitTextIntoChunksWithSemantics(text, chunkSize);
        List<ParentChildChunk> result = new ArrayList<>();

        for (int p = 0; p < parents.size(); p++) {
            String parent = parents.get(p);
            int parentId = p + 1;
            List<String> children = packSentencesIntoChunks(extractSentences(parent), childChunkSize);
            if (children.isEmpty()) {
                children = List.of(parent);
            }
            for (String child : children) {
                if (!child.isBlank()) {
                    result.add(new ParentChildChunk(child.trim(), parentId, parent));
                }
            }
        }
        return result;
    }

    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> sentences = extractSentences(text);
        if (sentences.isEmpty()) {
            return List.of();
        }

        List<String> chunks = packSentencesIntoChunks(sentences, chunkSize);
        return applySemanticOverlap(chunks, chunkSize);
    }

    private List<String> packSentencesIntoChunks(List<String> sentences, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.length() > chunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitLongSentence(sentence, chunkSize));
                continue;
            }
            if (current.length() + sentence.length() > chunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    private List<String> extractSentences(String text) {
        List<String> result = new ArrayList<>();
        BreakIterator bi = BreakIterator.getSentenceInstance(java.util.Locale.ROOT);
        bi.setText(text);
        int start = bi.first();
        int segmentCount = 0;
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String sentence = text.substring(start, end);
            if (!sentence.isBlank()) {
                result.add(sentence);
                segmentCount++;
            }
        }
        if (segmentCount <= 1 && text.contains("\n")) {
            result.clear();
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    result.add(line.trim());
                }
            }
        }
        return result;
    }

    private List<String> applySemanticOverlap(List<String> chunks, int chunkSize) {
        if (chunks.size() <= 1) {
            return chunks;
        }

        int overlapLimit = resolveOverlapLimit(chunkSize);
        if (overlapLimit <= 0) {
            return chunks;
        }

        List<String> overlapped = new ArrayList<>();
        overlapped.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String overlap = extractSemanticTail(chunks.get(i - 1), overlapLimit);
            String current = chunks.get(i);
            if (overlap.isBlank() || current.startsWith(overlap)) {
                overlapped.add(current);
            } else {
                overlapped.add(overlap + "\n\n" + current);
            }
        }

        return overlapped;
    }

    private int resolveOverlapLimit(int chunkSize) {
        if (chunkOverlapSize > 0) {
            return chunkOverlapSize;
        }
        return (int) Math.round(chunkSize * chunkOverlapRatio);
    }

    private int resolvedParentChunkSize() {
        return parentChunkSize > 0 ? parentChunkSize : chunkSize * 3;
    }

    private String extractSemanticTail(String text, int overlapLimit) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String trimmed = text.trim();
        String[] paragraphs = trimmed.split("\n\n+");
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String paragraph = paragraphs[i].trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() <= overlapLimit) {
                return paragraph;
            }
            String sentence = extractTailSentence(paragraph, overlapLimit);
            if (!sentence.isBlank()) {
                return sentence;
            }
        }
        return "";
    }

    private String extractTailSentence(String paragraph, int overlapLimit) {
        String[] sentences = paragraph.split("(?<=[.!?;])\\s+|(?<=[。！？；])");
        for (int i = sentences.length - 1; i >= 0; i--) {
            String sentence = sentences[i].trim();
            if (!sentence.isEmpty() && sentence.length() <= overlapLimit) {
                return sentence;
            }
        }
        return "";
    }

    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        BreakIterator wordIterator = BreakIterator.getWordInstance();
        wordIterator.setText(sentence);

        StringBuilder currentChunk = new StringBuilder();
        int start = wordIterator.first();
        for (int end = wordIterator.next(); end != BreakIterator.DONE; start = end, end = wordIterator.next()) {
            String word = sentence.substring(start, end);
            if (currentChunk.length() + word.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(word);
        }
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        logger.debug("Split long sentence: originalChars={}, chunks={}", sentence.length(), chunks.size());
        return chunks;
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("Memory usage is high: {}%", String.format("%.2f", memoryUsage * 100));
            System.gc();
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("Insufficient memory to process file. Current usage: "
                        + String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    private static final Set<String> BLOCK_ELEMENTS = Set.of(
            "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "td", "tr", "th", "section", "article", "header", "footer"
    );

    private static class StructureAwareContentHandler extends BodyContentHandler {
        private final StringBuilder target;

        StructureAwareContentHandler(StringBuilder target) {
            super(-1);
            this.target = target;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            if (BLOCK_ELEMENTS.contains(localName.toLowerCase())) {
                target.append("\n");
            }
            super.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (BLOCK_ELEMENTS.contains(localName.toLowerCase())) {
                target.append("\n");
            }
            super.endElement(uri, localName, qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            target.append(ch, start, length);
            super.characters(ch, start, length);
        }
    }
}
