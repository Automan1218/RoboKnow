package com.yizhaoqi.roboknow.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.roboknow.client.EmbeddingClient;
import com.yizhaoqi.roboknow.client.EmbeddingRequestBatcher;
import com.yizhaoqi.roboknow.entity.EsDocument;
import com.yizhaoqi.roboknow.entity.SearchResult;
import com.yizhaoqi.roboknow.model.DocumentVector;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import com.yizhaoqi.roboknow.repository.UserRepository;
import com.yizhaoqi.roboknow.repository.FileUploadRepository;
import com.yizhaoqi.roboknow.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private EmbeddingRequestBatcher embeddingRequestBatcher;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${search.embedding-cache.ttl-seconds:300}")
    private long embeddingCacheTtlSeconds;

    private static final String EMBEDDING_CACHE_PREFIX = "search:embedding:";

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);

        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            logger.debug("向量生成成功，开始执行 BM25 + 向量 RRF 混合检索");

            // 构建权限过滤查询（复用于 KNN filter 和 BM25 filter）
            co.elastic.clients.elasticsearch._types.query_dsl.Query permissionFilter = buildPermissionFilter(userDbId, userEffectiveTags);

            // 每一路多取候选，给 RRF 融合留出足够排名信息（业界 RRF 窗口惯例 50~100）
            final int candidateSize = Math.max(topK * 10, 100);

            // 第一路：向量近邻检索（ANN），捕捉语义相关
            SearchResponse<EsDocument> annResponse = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(ann -> ann
                        .field("vector")
                        .queryVector(queryVector)
                        .k(candidateSize)
                        .numCandidates(Math.max(candidateSize * 4, 100))
                        .filter(permissionFilter)
                );
                s.size(candidateSize);
                return s;
            }, EsDocument.class);

            // 第二路：BM25 关键词检索，捕捉精确 token 匹配（同一权限过滤）
            SearchResponse<EsDocument> bm25Response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                            .filter(permissionFilter)))
                    .size(candidateSize), EsDocument.class);

            logger.debug("ANN 命中 {} 条，BM25 命中 {} 条，开始 RRF 融合",
                    annResponse.hits().hits().size(), bm25Response.hits().hits().size());

            // Reciprocal Rank Fusion：按两路排名倒数加权融合，规避向量分与 BM25 分量纲不一致的问题
            List<SearchResult> results = rrfFuse(annResponse, bm25Response, topK).stream()
                    .filter(result -> isSearchResultAccessible(result, userDbId, userEffectiveTags))
                    .toList();

            logger.debug("RRF 融合后返回搜索结果数量: {}", results.size());
            attachFileNames(results);
            fetchParentContexts(results);
            return results;
        } catch (Exception e) {
            logger.error("带权限的搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    // 权限过滤
                                    .filter(f -> f
                                            .bool(bf -> bf
                                                    // 条件1: 用户可以访问自己的文档
                                                    .should(s1 -> s1
                                                            .term(t -> t
                                                                    .field("userId")
                                                                    .value(userDbId)
                                                            )
                                                    )
                                                    // 条件2: 用户可以访问公开的文档
                                                    .should(s2 -> s2
                                                            .term(t -> t
                                                                    .field("public")
                                                                    .value(true)
                                                            )
                                                    )
                                                    // 条件3: 用户可以访问其所属组织的文档（包含层级关系）
                                                    .should(s3 -> {
                                                        if (userEffectiveTags.isEmpty()) {
                                                            return s3.matchNone(mn -> mn);
                                                        } else if (userEffectiveTags.size() == 1) {
                                                            // 单个标签使用 term 查询
                                                            return s3.term(t -> t
                                                                    .field("orgTag")
                                                                    .value(userEffectiveTags.get(0))
                                                            );
                                                        } else {
                                                            // 多个标签使用 bool should 组合多个 term 查询
                                                            return s3.bool(innerBool -> {
                                                                userEffectiveTags.forEach(tag ->
                                                                        innerBool.should(sh -> sh.term(t -> t
                                                                                .field("orgTag")
                                                                                .value(tag)
                                                                        ))
                                                                );
                                                                return innerBool;
                                                            });
                                                        }
                                                    })
                                            )
                                    )
                            )
                    )
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("纯文本搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}",
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(),
                            hit.source().getTextContent().substring(0, Math.min(50, hit.source().getTextContent().length())));
                        return toSearchResult(hit.source(), hit.score());
                    })
                    .filter(result -> isSearchResultAccessible(result, userDbId, userEffectiveTags))
                    .toList();

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            attachFileNames(results);
            fetchParentContexts(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(ann -> ann
                        .field("vector")
                        .queryVector(queryVector)
                        .k(topK)
                        .numCandidates(Math.max(topK * 4, 50))
                );
                s.size(topK);
                return s;
            }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score()
                        );
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q
                        .match(m -> m
                                .field("textContent")
                                .query(query)
                        )
                )
                .size(topK),
                EsDocument.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score()
                    );
                })
                .toList();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null。
     *
     * 查询文本 -> 向量的映射与用户/权限无关（同一句 query 无论谁来问，向量都一样），
     * 所以只缓存向量本身，不缓存最终检索结果——权限过滤永远基于当前请求的用户实时
     * 跑在 ES 那一层，不会因为缓存而把 A 用户的私有文档结果泄露给 B 用户。
     * 命中缓存直接省掉一次外部 embedding API 往返（真实压测里这是检索延迟的大头）。
     */
    @SuppressWarnings("unchecked")
    private List<Float> embedToVectorList(String text) {
        String cacheKey = EMBEDDING_CACHE_PREFIX + md5Hex(text);
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof List<?> cachedList) {
                logger.debug("查询向量缓存命中: query={}", text);
                return (List<Float>) cachedList;
            }
        } catch (Exception e) {
            logger.warn("读取查询向量缓存失败，回退到实时生成: {}", e.getMessage());
        }

        try {
            // 缓存未命中：走批量合并器而不是直接单条调用 API。短 debounce 窗口内
            // 并发涌入的、彼此不同的 query 会被打包成一次 API 调用，降低突发并发下
            // 外部调用次数（跟上面的缓存互补：缓存解决"重复问"，这里解决"同时问不同问题"）。
            float[] raw = embeddingRequestBatcher.requestEmbedding(text).get(30, TimeUnit.SECONDS);
            if (raw == null || raw.length == 0) {
                logger.warn("生成的向量为空");
                return null;
            }
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }

            try {
                redisTemplate.opsForValue().set(cacheKey, list, Duration.ofSeconds(embeddingCacheTtlSeconds));
            } catch (Exception e) {
                logger.warn("写入查询向量缓存失败（不影响本次搜索）: {}", e.getMessage());
            }

            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    private String md5Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 标配算法，不会真的抛出；兜底用 hashCode 避免编译期异常处理负担
            return Integer.toHexString(text.hashCode());
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    /**
     * 构建权限过滤 Query，同时用于 KNN filter 和 query.filter。
     * 文档可见条件：用户自己的文档 OR 公开文档 OR 用户所属组织的文档。
     */
    private co.elastic.clients.elasticsearch._types.query_dsl.Query buildPermissionFilter(
            String userDbId, List<String> userEffectiveTags) {
        return co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q.bool(bf -> {
            bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
            bf.should(s2 -> s2.term(t -> t.field("public").value(true)));
            if (userEffectiveTags.isEmpty()) {
                bf.should(s3 -> s3.matchNone(mn -> mn));
            } else if (userEffectiveTags.size() == 1) {
                bf.should(s3 -> s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0))));
            } else {
                bf.should(s3 -> s3.bool(inner -> {
                    userEffectiveTags.forEach(tag -> inner.should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                    return inner;
                }));
            }
            return bf;
        }));
    }

    /** RRF 融合的排名衰减常数，行业惯例取 60。 */
    private static final int RRF_K = 60;

    /**
     * Reciprocal Rank Fusion：对两路检索结果按排名倒数 1/(k+rank) 累加打分再排序。
     * 用排名而非原始分数融合，天然规避向量余弦分与 BM25 分量纲不一致的问题。
     * 同一子块以 fileMd5#chunkId 作为唯一键去重。
     */
    private List<SearchResult> rrfFuse(SearchResponse<EsDocument> annResp,
            SearchResponse<EsDocument> bm25Resp, int topK) {
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, EsDocument> docs = new LinkedHashMap<>();
        accumulateRrf(annResp, fused, docs);
        accumulateRrf(bm25Resp, fused, docs);

        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> toSearchResult(docs.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    private void accumulateRrf(SearchResponse<EsDocument> resp,
            Map<String, Double> fused, Map<String, EsDocument> docs) {
        int rank = 1;
        for (var hit : resp.hits().hits()) {
            EsDocument src = hit.source();
            if (src == null) {
                continue;
            }
            String key = src.getFileMd5() + "#" + src.getChunkId();
            fused.merge(key, 1.0 / (RRF_K + rank), Double::sum);
            docs.putIfAbsent(key, src);
            rank++;
        }
    }

    /**
     * 将 ES 命中（子块）转换为 SearchResult，携带父块信息用于 small-to-big 回溯。
     */
    private SearchResult toSearchResult(EsDocument source, Double score) {
        SearchResult result = new SearchResult(
                source.getFileMd5(),
                source.getChunkId(),
                source.getTextContent(),
                score,
                source.getUserId(),
                source.getOrgTag(),
                source.isPublic()
        );
        result.setParentChunkId(source.getParentChunkId());
        result.setParentContent(source.getParentContent());
        return result;
    }

    private boolean isSearchResultAccessible(SearchResult result, String userDbId, List<String> userEffectiveTags) {
        if (result == null) return false;
        if (userDbId != null && userDbId.equals(result.getUserId())) return true;
        if (Boolean.TRUE.equals(result.getIsPublic())) return true;
        if (userEffectiveTags != null && result.getOrgTag() != null
                && userEffectiveTags.contains(result.getOrgTag())) return true;
        return false;
    }

    private void fetchParentContexts(List<SearchResult> results) {
        List<Long> parentIds = results.stream()
                .map(SearchResult::getParentChunkId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (parentIds.isEmpty()) return;

        Map<Long, String> parentTexts = documentVectorRepository.findAllById(parentIds)
                .stream()
                .collect(Collectors.toMap(DocumentVector::getVectorId, DocumentVector::getTextContent));

        results.forEach(r -> {
            if (r.getParentChunkId() != null) {
                String ctx = parentTexts.get(r.getParentChunkId());
                if (ctx != null) r.setContextText(ctx);
            }
        });
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
