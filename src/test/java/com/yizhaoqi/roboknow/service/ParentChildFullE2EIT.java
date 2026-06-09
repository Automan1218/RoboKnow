package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.agent.AgentContext;
import com.yizhaoqi.roboknow.agent.AnswerGroundingService;
import com.yizhaoqi.roboknow.agent.tool.ToolRegistry;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真·全链路 E2E：真实 PDF → 父子分块入真实 MySQL → 真实 embedding 入真实 ES
 *               → 真实向量召回子块 + small-to-big 回溯父块 → 真实 LLM 回答 prompt「我有几段实习」
 *               → 断言答案给出 2 段实习。
 *
 * 需要 OPENAI_API_KEY（embedding + LLM 都要）。未设置则自动跳过（assumeTrue），不阻断 CI。
 * 跑法： $env:OPENAI_API_KEY="sk-..."; mvn -Dtest=ParentChildFullE2EIT test
 *
 * 会短暂写入真实 knowledge_base 索引与 document_vectors 表，@AfterEach 按 MD5 清理。
 */
@SpringBootTest
@ActiveProfiles("dev")
class ParentChildFullE2EIT {

    private static final Path PDF_PATH = Path.of("docs", "henry-hou-cv (2).pdf");
    private static final String MD5 = "abcdef0123456789abcdef0123456789"; // 32 chars (file_md5 VARCHAR(32))
    private static final String USER = "admin";          // 启动时自动创建
    private static final String PROMPT = "我有几段实习？";   // 题面要求的 prompt
    private static final String SEARCH_QUERY = "实习经历 internship Full Stack Intern experience";

    private static final Pattern TWO = Pattern.compile("\\b(two|2)\\b|两段|两个|两次", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERN = Pattern.compile("intern|实习", Pattern.CASE_INSENSITIVE);

    @Autowired private ParseService parseService;
    @Autowired private VectorizationService vectorizationService;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private AnswerGroundingService answerGroundingService;
    @Autowired private ElasticsearchService elasticsearchService;
    @Autowired private DocumentVectorRepository documentVectorRepository;

    @BeforeEach
    void requireKey() {
        String key = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "OPENAI_API_KEY 未设置：跳过真实 embedding/LLM 全链路 E2E");
        cleanup();
    }

    @AfterEach
    void cleanup() {
        try { elasticsearchService.deleteByFileMd5(MD5); } catch (Exception ignored) {}
        try { documentVectorRepository.deleteByFileMd5(MD5); } catch (Exception ignored) {}
    }

    @Test
    void fullPipeline_promptHowManyInternships_answersTwo() throws Exception {
        // 1) 解析 + 父子分块 → 真实 MySQL
        try (InputStream in = Files.newInputStream(PDF_PATH)) {
            parseService.parseAndSave(MD5, in, USER, "default", true);
        }
        long children = documentVectorRepository.findByFileMd5(MD5).size();
        assertTrue(children > 0, "父子分块未落库");

        // 2) 向量化（真实 embedding）→ 真实 ES knowledge_base
        vectorizationService.vectorize(MD5, USER, "default", true);
        Thread.sleep(1500); // 等 ES 刷新

        // 3) 真实向量召回子块 + HybridSearchTool 的 small-to-big 回溯父块
        AgentContext ctx = new AgentContext(USER, PROMPT, "e2e-conv", new ArrayList<>(), null);
        String observation = toolRegistry.execute("hybrid_search", SEARCH_QUERY, ctx);
        System.out.println("===== 召回观测(父块回溯) =====\n" + observation);
        assertTrue(INTERN.matcher(observation).find(), "召回上下文未含实习内容");

        // 4) 真实 LLM 基于召回证据回答 prompt「我有几段实习」
        String answer = answerGroundingService.groundAnswer(
                PROMPT, "Summarize the internship count from the evidence.", List.of(observation));
        System.out.println("===== LLM 答案 =====\n" + answer);

        // 5) 断言：答案指出 2 段实习
        assertNotNull(answer);
        assertTrue(INTERN.matcher(answer).find(), "答案未提及实习/intern");
        assertTrue(TWO.matcher(answer).find(),
                "答案未给出 2 段实习，实际答案: " + answer);
        System.out.println("✅ 全链路 E2E：prompt「我有几段实习」→ 答案含 2 段实习");
    }
}
