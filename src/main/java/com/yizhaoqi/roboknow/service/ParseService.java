package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.DocumentVector;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.text.BreakIterator;

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

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;
    
    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;
    
    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;
    
    public ParseService() {
    }

    /**
     * 以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
     * 采用"父文档-子切片"策略。
     *
     * @param fileMd5    文件的MD5哈希值，用于唯一标识文件
     * @param fileStream 文件输入流，用于读取文件内容
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);
        
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            // 创建一个流式处理器，它会在内部处理父块的切分和子块的保存
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            // Tika的parse方法会驱动整个流式处理过程
            // 当handler的characters方法接收到足够数据时，会触发分块、切片和保存
            parser.parse(bufferedStream, handler, metadata, context);

            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);

        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        // 使用默认值调用新方法
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memoryUsage = (double) usedMemory / maxMemory;
        
        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();
            
            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " + 
                    String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }
    
    /**
     * 内部流式内容处理器，实现了父子文档切分策略的核心逻辑。
     * Tika解析器会调用characters方法，当累积的文本达到"父块"大小时，
     * 就触发processParentChunk方法，进行"子切片"的生成和入库。
     */
    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private int savedChunkCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1); // 禁用Tika的内部写入限制，我们自己管理缓冲区
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            // 处理文档末尾剩余的最后一部分内容
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            String parentChunkText = buffer.toString();
            logger.debug("处理父文本块，大小: {} bytes", parentChunkText.length());

            // 1. 将父块分割成更小的、有语义的子切片
            List<String> childChunks = ParseService.this.splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);

            // 2. 将子切片批量保存到数据库
            this.savedChunkCount = ParseService.this.saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, this.savedChunkCount);

            // 3. 清空缓冲区，为下一个父块做准备
            buffer.setLength(0);
        }
    }

    /**
     * 将子切片列表保存到数据库。
     *
     * @param fileMd5         文件的 MD5 哈希值
     * @param chunks          子切片文本列表
     * @param userId          上传用户ID
     * @param orgTag          组织标签
     * @param isPublic        是否公开
     * @param startingChunkId 当前批次的起始分片ID
     * @return 保存后总的分片数量
     */
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

    /**
     * 语义分割：用 BreakIterator.getSentenceInstance 切句，再按 chunkSize 滑动聚合。
     * 不依赖换行结构，对 PDF/Word 解析后的英文和中文文本均有效。
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> sentences = extractSentences(text);
        if (sentences.isEmpty()) return List.of();

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            // 单句超过 chunkSize：直接切词后加入
            if (sentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitLongSentence(sentence, chunkSize));
                continue;
            }
            // 加入本句会超限：先提交当前块，再开新块
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
     * 用 BreakIterator 提取句子列表，自动适配英文/中文语言边界。
     * 对无明确句点的简历 bullet 行，BreakIterator 会在换行处断句。
     */
    private List<String> extractSentences(String text) {
        List<String> result = new ArrayList<>();
        BreakIterator bi = BreakIterator.getSentenceInstance(java.util.Locale.ROOT);
        bi.setText(text);
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String sentence = text.substring(start, end);
            if (!sentence.isBlank()) {
                result.add(sentence);
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
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");
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
            if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(word);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        logger.debug("BreakIterator分词成功，原文长度: {}, 分块数: {}", sentence.length(), chunks.size());
        return chunks;
    }
    
    /**
     * 备用方案：按字符分割
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
