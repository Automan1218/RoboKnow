package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.client.EmbeddingClient;
import com.yizhaoqi.roboknow.entity.EsDocument;
import com.yizhaoqi.roboknow.model.DocumentVector;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
            logger.info("Start vectorizing file: fileMd5={}, userId={}, orgTag={}, isPublic={}",
                    fileMd5, userId, orgTag, isPublic);

            List<DocumentVector> childChunks = documentVectorRepository.findByFileMd5AndIsParentFalse(fileMd5);
            if (childChunks.isEmpty()) {
                logger.warn("No child chunks found for fileMd5={}", fileMd5);
                return;
            }

            List<String> texts = childChunks.stream()
                    .map(DocumentVector::getTextContent)
                    .toList();

            List<float[]> vectors = embeddingClient.embed(texts);

            List<EsDocument> esDocuments = IntStream.range(0, childChunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            childChunks.get(i).getChunkId(),
                            childChunks.get(i).getTextContent(),
                            vectors.get(i),
                            "openai-text-embedding-3-large",
                            userId,
                            orgTag,
                            isPublic,
                            childChunks.get(i).getParentChunkId()
                    ))
                    .toList();

            elasticsearchService.bulkIndex(esDocuments);

            logger.info("Vectorization finished: fileMd5={}, childChunks={}", fileMd5, childChunks.size());
        } catch (Exception e) {
            logger.error("Vectorization failed: fileMd5={}", fileMd5, e);
            throw new RuntimeException("Vectorization failed", e);
        }
    }
}
