package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.DocumentVector;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import jakarta.annotation.PostConstruct;
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
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.*;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.chunk-overlap-ratio:0.1}")
    private double chunkOverlapRatio;

    @Value("${file.parsing.chunk-overlap-size:0}")
    private int chunkOverlapSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    @Value("${ocr.api.url:http://localhost:8000}")
    private String ocrApiUrl;

    @Value("${ocr.api.min-text-length:100}")
    private int ocrMinTextLength;

    @Value("${ocr.api.timeout-seconds:120}")
    private int ocrTimeoutSeconds;

    private WebClient ocrWebClient;

    @PostConstruct
    public void initOcrClient() {
        this.ocrWebClient = WebClient.builder()
            .baseUrl(ocrApiUrl)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
            .build();
    }

    public ParseService() {}

    // ─────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────

    /**
     * Parse file and save chunks.
     * PDF: Tika first → if too little text (image PDF) → PaddleOCR fallback.
     * Other formats: Tika only.
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);

        checkMemoryThreshold();

        byte[] fileBytes = fileStream.readAllBytes();
        String text = extractText(fileBytes, fileMd5);

        if (text == null || text.isBlank()) {
            logger.warn("文件内容为空，跳过处理: fileMd5={}", fileMd5);
            return;
        }

        List<String> chunks = splitTextIntoChunksWithSemantics(text, chunkSize);
        saveChildChunks(fileMd5, chunks, userId, orgTag, isPublic, 0);
        logger.info("文件解析完成，fileMd5: {}, 共 {} 个chunks", fileMd5, chunks.size());
    }

    /** Backward-compatible overload */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    // ─────────────────────────────────────────────────────────
    // Text extraction
    // ─────────────────────────────────────────────────────────

    private String extractText(byte[] fileBytes, String fileMd5) {
        String tikaText = extractWithTika(fileBytes, fileMd5);

        if (tikaText.trim().length() < ocrMinTextLength && isPdf(fileBytes)) {
            logger.info("Tika提取文字不足({} chars)，尝试PaddleOCR: fileMd5={}", tikaText.trim().length(), fileMd5);
            String ocrText = callPaddleOcr(fileBytes, fileMd5);
            if (ocrText != null && !ocrText.isBlank()) {
                logger.info("PaddleOCR提取成功: {} chars", ocrText.length());
                return ocrText;
            }
            logger.warn("PaddleOCR失败，使用Tika结果: fileMd5={}", fileMd5);
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

            logger.debug("Tika提取文字: {} chars, fileMd5={}", sb.length(), fileMd5);
            return sb.toString();
        } catch (SAXException e) {
            logger.error("Tika SAX解析失败: fileMd5={}", fileMd5, e);
            return "";
        } catch (Exception e) {
            logger.error("Tika解析失败: fileMd5={}", fileMd5, e);
            return "";
        }
    }

    private boolean isPdf(byte[] fileBytes) {
        // PDF magic bytes: %PDF
        return fileBytes.length >= 4
            && fileBytes[0] == 0x25  // %
            && fileBytes[1] == 0x50  // P
            && fileBytes[2] == 0x44  // D
            && fileBytes[3] == 0x46; // F
    }

    private String callPaddleOcr(byte[] fileBytes, String fileMd5) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() { return "document.pdf"; }
            }).contentType(MediaType.APPLICATION_PDF);

            Map<?, ?> response = ocrWebClient.post()
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
            logger.error("PaddleOCR调用失败: fileMd5={}", fileMd5, e);
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────
    // Structure-aware Tika content handler
    // Inserts \n on block-level elements so extractSentences()
    // can split on \n even when there is no sentence punctuation.
    // ─────────────────────────────────────────────────────────

    private static final Set<String> BLOCK_ELEMENTS = Set.of(
        "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "td", "tr", "th", "section", "article", "header", "footer"
    );

    private static class StructureAwareContentHandler extends BodyContentHandler {
        private final StringBuilder target;

        StructureAwareContentHandler(StringBuilder target) {
            super(-1); // unlimited internal buffer
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

    // ─────────────────────────────────────────────────────────
    // Chunk persistence
    // ─────────────────────────────────────────────────────────

    private int saveChildChunks(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic, int startingChunkId) {
        int currentChunkId = startingChunkId;
        for (String chunk : chunks) {
            currentChunkId++;
            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库", chunks.size());
        return currentChunkId;
    }

    // ─────────────────────────────────────────────────────────
    // Semantic chunking
    // ─────────────────────────────────────────────────────────

    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> sentences = extractSentences(text);
        if (sentences.isEmpty()) return List.of();

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitLongSentence(sentence, chunkSize));
                continue;
            }
            if (current.length() + sentence.length() > chunkSize && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return applySemanticOverlap(chunks, chunkSize);
    }

    /**
     * Extract sentences.
     * 1. Try BreakIterator (sentence punctuation).
     * 2. If ≤1 segment and text has \n → split by line (handles structured docs
     *    like resumes where Tika / OCR preserves line breaks but not punctuation).
     */
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
        if (chunks.size() <= 1) return chunks;

        int overlapLimit = resolveOverlapLimit(chunkSize);
        if (overlapLimit <= 0) return chunks;

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
        if (chunkOverlapSize > 0) return chunkOverlapSize;
        return (int) Math.round(chunkSize * chunkOverlapRatio);
    }

    private String extractSemanticTail(String text, int overlapLimit) {
        if (text == null || text.isBlank()) return "";

        String trimmed = text.trim();
        String[] paragraphs = trimmed.split("\n\n+");
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String paragraph = paragraphs[i].trim();
            if (paragraph.isEmpty()) continue;
            if (paragraph.length() <= overlapLimit) return paragraph;
            String sentence = extractTailSentence(paragraph, overlapLimit);
            if (!sentence.isBlank()) return sentence;
        }
        return "";
    }

    private String extractTailSentence(String paragraph, int overlapLimit) {
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");
        for (int i = sentences.length - 1; i >= 0; i--) {
            String sentence = sentences[i].trim();
            if (!sentence.isEmpty() && sentence.length() <= overlapLimit) return sentence;
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
            if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(word);
        }
        if (!currentChunk.isEmpty()) chunks.add(currentChunk.toString());

        logger.debug("BreakIterator分词: 原文 {} chars, 分块 {}", sentence.length(), chunks.size());
        return chunks;
    }

    // ─────────────────────────────────────────────────────────
    // Memory guard
    // ─────────────────────────────────────────────────────────

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发GC", memoryUsage * 100);
            System.gc();
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理文件。当前使用率: "
                    + String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }
}
