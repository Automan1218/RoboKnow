package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.client.EmbeddingClient;
import com.yizhaoqi.roboknow.model.DocumentVector;
import com.yizhaoqi.roboknow.entity.EsDocument;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                       fileMd5, userId, orgTag, isPublic);

            List<DocumentVector> childChunks = documentVectorRepository.findByFileMd5AndIsParentFalse(fileMd5);
            if (childChunks.isEmpty()) {
                logger.warn("未找到子切片内容，fileMd5: {}", fileMd5);
                return;
            }

            List<String> texts = childChunks.stream()
                    .map(DocumentVector::getTextContent)
                    .toList();

            List<float[]> vectors = embeddingClient.embed(texts);

            // 构建 Elasticsearch 文档并存储
            // 向量化的是子块内容（getContent），父块全文（parentContent）随文档一并存储，
            // 供召回后回溯（small-to-big）喂给 LLM。
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),
                            chunks.get(i).getParentChunkId(),
                            chunks.get(i).getParentContent(),
                            vectors.get(i),
                            "openai-text-embedding-3-large",
                            userId,
                            orgTag,
                            isPublic,
                            childChunks.get(i).getParentChunkId()
                    ))
                    .toList();

            elasticsearchService.bulkIndex(esDocuments);

            logger.info("向量化完成，fileMd5: {}, 子切片数: {}", fileMd5, childChunks.size());
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("向量化失败", e);
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5);

        // 转换为 TextChunk 列表（携带父子分块信息）
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getParentChunkId(),
                        vector.getParentContent()
                ))
                .toList();
    }
}