package com.yizhaoqi.roboknow.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.yizhaoqi.roboknow.entity.EsDocument;
import com.yizhaoqi.roboknow.service.ParseService.ParentChildChunk;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真·Elasticsearch 集成测试：验证父子分块在真实 ES 上的 small-to-big 检索。
 *
 * 不需要 OpenAI（无 embedding / LLM）——用 BM25 文本召回子块代替向量召回，
 * 但走的是真实 ES：真实建索引、真实写入（含 parentContent 字段）、真实检索读回 _source。
 *
 * 链路：真实简历 PDF → 父子分块 → 子块写入真实 ES（携带 parentContent）
 *      → BM25 召回子块 → 从命中读回 parentContent → small-to-big 按父块去重
 *      → 断言回溯上下文恰含 2 段实习（即 prompt「我有几段实习」可得到 2 段答案）。
 *
 * 用独立索引 pc_resume_it，跑完即删，不污染线上 knowledge_base。
 * ES 不可达时自动跳过（assumeTrue），不阻断无 ES 的 CI。
 */
class ParentChildElasticsearchIT {

    private static final String INDEX = "pc_resume_it";
    private static final Path PDF_PATH = Path.of("docs", "henry-hou-cv (2).pdf");
    private static final String MD5 = "itresume0000000000000000000000aa";

    private static final Pattern ROLE = Pattern.compile("Full\\s*Stack\\s*Intern", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD_2026 = Pattern.compile("Mar\\s*2026\\s*[-–]\\s*Present", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD_2025 = Pattern.compile("Mar\\s*2025\\s*[-–]\\s*Aug\\s*2025", Pattern.CASE_INSENSITIVE);

    private static final String MAPPING = """
        {
          "mappings": {
            "properties": {
              "fileMd5":       {"type":"keyword"},
              "chunkId":       {"type":"integer"},
              "textContent":   {"type":"text","analyzer":"standard"},
              "parentChunkId": {"type":"integer"},
              "parentContent": {"type":"text","index":false}
            }
          }
        }
        """;

    private static ElasticsearchClient es;
    private static int childCount;
    private static long parentCount;

    @BeforeAll
    static void setup() throws Exception {
        es = buildClient();
        Assumptions.assumeTrue(pingable(es), "Elasticsearch 不可达，跳过集成测试");

        // 1) 真实 PDF → Tika 抽取 → 父子分块
        byte[] bytes = Files.readAllBytes(PDF_PATH);
        ParseService parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "chunkSize", 1024);
        ReflectionTestUtils.setField(parseService, "childChunkSize", 256);
        ReflectionTestUtils.setField(parseService, "chunkOverlapSize", 0);
        ReflectionTestUtils.setField(parseService, "chunkOverlapRatio", 0.0);
        String text = (String) ReflectionTestUtils.invokeMethod(parseService, "extractWithTika", bytes, MD5);
        List<ParentChildChunk> chunks = parseService.splitIntoParentChildChunks(text);
        childCount = chunks.size();
        parentCount = chunks.stream().map(c -> c.parentChunkId).distinct().count();

        // 2) 真实建索引（parentContent index:false，验证非索引字段也能存取）
        if (es.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value()) {
            es.indices().delete(DeleteIndexRequest.of(d -> d.index(INDEX)));
        }
        es.indices().create(CreateIndexRequest.of(c -> c.index(INDEX).withJson(new StringReader(MAPPING))));

        // 3) 真实写入：每个子块一条文档，携带父块全文（生产同款 EsDocument 序列化）
        List<BulkOperation> ops = new ArrayList<>();
        int childId = 0;
        for (ParentChildChunk c : chunks) {
            childId++;
            EsDocument doc = new EsDocument(
                    UUID.randomUUID().toString(), MD5, childId, c.childContent,
                    c.parentChunkId, c.parentContent,
                    null, "no-embedding-bm25-only", "admin", "default", true);
            ops.add(BulkOperation.of(op -> op.index(idx -> idx.index(INDEX).id(doc.getId()).document(doc))));
        }
        es.bulk(BulkRequest.of(b -> b.operations(ops)));
        es.indices().refresh(r -> r.index(INDEX));

        System.out.println("===== 真实 ES 集成诊断 =====");
        System.out.println("父块数: " + parentCount + ", 写入子块文档数: " + childCount);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (es != null && es.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value()) {
            es.indices().delete(DeleteIndexRequest.of(d -> d.index(INDEX)));
        }
    }

    /**
     * 核心：BM25 召回子块 → small-to-big 回溯父块 → 回溯上下文含 2 段实习。
     * 复刻 HybridSearchTool.expandToParents 的去重逻辑（按 fileMd5+parentChunkId）。
     */
    @Test
    void realEs_smallToBig_yieldsTwoInternships() throws Exception {
        // 子块 BM25 召回（向量召回的无 key 替身）
        SearchResponse<EsDocument> resp = es.search(s -> s
                .index(INDEX)
                .size(10)
                .query(q -> q.match(m -> m.field("textContent").query("Full Stack Intern internship experience"))),
                EsDocument.class);

        List<EsDocument> hits = resp.hits().hits().stream().map(h -> h.source()).toList();
        assertTrue(hits.size() >= 2, "ES 应召回 >=2 个 intern 相关子块，实际: " + hits.size());

        // small-to-big：按 (fileMd5, parentChunkId) 去重，读回父块全文
        Map<String, String> parents = new LinkedHashMap<>();
        boolean expanded = false;
        for (EsDocument d : hits) {
            assertTrue(d.getParentContent() != null && !d.getParentContent().isBlank(),
                    "ES 未返回 parentContent（字段未存储/读回失败）");
            String key = d.getFileMd5() + "#p" + d.getParentChunkId();
            parents.putIfAbsent(key, d.getParentContent());
            if (d.getParentContent().length() > d.getTextContent().length()) expanded = true;
        }

        String context = String.join("\n", parents.values());
        int roleCount = count(ROLE, context);

        System.out.println("BM25 命中子块: " + hits.size() + ", 回溯去重父块: " + parents.size());
        System.out.println("回溯上下文中 Full Stack Intern 次数: " + roleCount);
        System.out.println("\n========== 喂给 LLM 的真实回溯上下文 (prompt: 我有几段实习) ==========");
        int idx = 0;
        for (String p : parents.values()) {
            System.out.println("----- 父块 #" + (++idx) + " -----\n" + p.trim() + "\n");
        }
        System.out.println("==================== 上下文结束 ====================\n");

        assertTrue(expanded, "small-to-big 未放大上下文（parentContent 未大于子块）");
        assertTrue(PERIOD_2026.matcher(context).find(), "回溯上下文缺少实习一 Mar 2026 - Present");
        assertTrue(PERIOD_2025.matcher(context).find(), "回溯上下文缺少实习二 Mar 2025 - Aug 2025");
        assertEquals(2, roleCount, "回溯上下文应含 2 段 Full Stack Intern（答案=2 段实习），实际: " + roleCount);

        System.out.println("✅ 真实 ES 链路：父子分块回溯上下文含 2 段实习");
    }

    // ───────────── helpers ─────────────

    private static ElasticsearchClient buildClient() {
        BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("elastic", "PaiSmart2025"));
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200, "http"))
                .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(creds))
                .build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    private static boolean pingable(ElasticsearchClient client) {
        try {
            return client.ping().value();
        } catch (Exception e) {
            return false;
        }
    }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
