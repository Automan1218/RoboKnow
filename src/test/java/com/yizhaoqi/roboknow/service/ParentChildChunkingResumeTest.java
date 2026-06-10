package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.service.ParseService.ParentChildChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端验证父子分块（small-to-big）在真实简历 PDF 上的效果。
 *
 * 业务场景：用户提问「我有几段实习」，期望答案为 2 段（简历里有两段 Full Stack Intern 经历）。
 *
 * 本测试不依赖 ES / 向量服务 / LLM：
 *   1. 用生产同款 Tika 路径抽取真实 PDF 文本；
 *   2. 跑父子分块 splitIntoParentChildChunks；
 *   3. 用词法匹配模拟「子块向量召回」（query=intern），再 small-to-big 回溯到父块去重；
 *   4. 断言回溯出的父块集合完整包含两段实习（两个不同时间段 + 两次 Full Stack Intern），
 *      即 LLM 拿到这些上下文后能数出 2 段实习。
 *
 * 词法匹配只是向量召回的确定性替身，验证的是父子分块「召回小、上下文大」这一机制本身。
 */
class ParentChildChunkingResumeTest {

    private static final Path PDF_PATH = Path.of("docs", "henry-hou-cv (2).pdf");

    // 两段实习的判别特征：角色名 + 两个互不相同的时间段
    private static final Pattern ROLE = Pattern.compile("Full\\s*Stack\\s*Intern", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD_2026 = Pattern.compile("Mar\\s*2026\\s*[-–]\\s*Present", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD_2025 = Pattern.compile("Mar\\s*2025\\s*[-–]\\s*Aug\\s*2025", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERN = Pattern.compile("intern", Pattern.CASE_INSENSITIVE);

    private static String resumeText;
    private static List<ParentChildChunk> chunks;

    @BeforeAll
    static void parseAndChunk() throws Exception {
        assertTrue(Files.exists(PDF_PATH), "测试简历缺失: " + PDF_PATH.toAbsolutePath());
        byte[] bytes = Files.readAllBytes(PDF_PATH);

        ParseService parseService = new ParseService();
        // 父块 1024 / 子块 256，关闭重叠让断言更干净（resolveOverlapLimit 取 0）
        ReflectionTestUtils.setField(parseService, "chunkSize", 1024);
        ReflectionTestUtils.setField(parseService, "childChunkSize", 256);
        ReflectionTestUtils.setField(parseService, "chunkOverlapSize", 0);
        ReflectionTestUtils.setField(parseService, "chunkOverlapRatio", 0.0);
        ReflectionTestUtils.setField(parseService, "parentChildEnabled", true);

        // 复用生产同款 Tika 抽取（StructureAwareContentHandler），私有方法走反射
        resumeText = (String) ReflectionTestUtils.invokeMethod(
                parseService, "extractWithTika", bytes, "henry-hou-cv");
        assertNotNull(resumeText, "Tika 抽取返回 null");

        chunks = parseService.splitIntoParentChildChunks(resumeText);

        long parentCount = chunks.stream().map(c -> c.parentChunkId).distinct().count();
        System.out.println("===== 父子分块诊断 =====");
        System.out.println("简历文本长度: " + resumeText.length());
        System.out.println("父块数: " + parentCount + ", 子块数: " + chunks.size());
    }

    /** 抽取的真值：简历里确实有 2 段 Full Stack Intern，时间段互不相同。 */
    @Test
    void groundTruth_resumeHasTwoInternships() {
        assertEquals(2, count(ROLE, resumeText), "简历真值应有 2 次 Full Stack Intern");
        assertTrue(PERIOD_2026.matcher(resumeText).find(), "缺少实习时间段 Mar 2026 - Present");
        assertTrue(PERIOD_2025.matcher(resumeText).find(), "缺少实习时间段 Mar 2025 - Aug 2025");
    }

    /** 父子分块不丢内容：所有父块拼起来仍完整保留两段实习。 */
    @Test
    void parentChunks_preserveBothInternships() {
        String allParents = distinctParents(chunks).values().stream()
                .reduce("", (a, b) -> a + "\n" + b);
        assertEquals(2, count(ROLE, allParents), "父块全集应保留 2 次 Full Stack Intern");
        assertTrue(PERIOD_2026.matcher(allParents).find(), "父块全集缺少 Mar 2026 - Present");
        assertTrue(PERIOD_2025.matcher(allParents).find(), "父块全集缺少 Mar 2025 - Aug 2025");
    }

    /** 子块整完整性：每个子块非空、有父块编号、且内容落在其父块文本内。 */
    @Test
    void childChunks_areConsistentWithParents() {
        for (ParentChildChunk c : chunks) {
            assertTrue(c.childContent != null && !c.childContent.isBlank(), "子块为空");
            assertTrue(c.parentChunkId >= 1, "父块编号非法: " + c.parentChunkId);
            assertNotNull(c.parentContent, "父块文本为空");
            // 子块由父块的句子重新分段拼接而成，句界处空白可能被规整（trim/换行差异），
            // 但非空白字符完全一致——故按"去掉所有空白"判断子块是否落在父块内。
            String normChild = stripWhitespace(c.childContent);
            String normParent = stripWhitespace(c.parentContent);
            assertTrue(normParent.contains(normChild),
                    "子块不在其父块内\n子块: " + c.childContent + "\n父块: " + c.parentContent);
        }
    }

    /**
     * 核心断言：模拟「我有几段实习」的检索。
     * 子块词法命中 intern → small-to-big 回溯父块去重 → 父块集合包含两段实习。
     * 这正是 LLM 能答出「2 段」所需的上下文。
     */
    @Test
    void smallToBigRetrieval_yieldsTwoInternships() {
        // 1) 子块召回（向量召回的确定性替身）
        List<ParentChildChunk> hits = chunks.stream()
                .filter(c -> INTERN.matcher(c.childContent).find())
                .toList();
        assertTrue(hits.size() >= 2, "应至少召回 2 个提到 intern 的子块，实际: " + hits.size());

        // 2) small-to-big：按父块去重回溯
        Map<Integer, String> retrievedParents = new LinkedHashMap<>();
        boolean expandedAtLeastOnce = false;
        for (ParentChildChunk hit : hits) {
            retrievedParents.putIfAbsent(hit.parentChunkId, hit.parentContent);
            if (hit.parentContent.length() > hit.childContent.length()) {
                expandedAtLeastOnce = true; // 父块确实比子块大 → 上下文被放大
            }
        }

        System.out.println("intern 命中子块数: " + hits.size()
                + ", 回溯去重后父块数: " + retrievedParents.size());

        // 3) 回溯出的父块集合应覆盖两段实习
        String retrievedContext = String.join("\n", retrievedParents.values());
        int roleCount = count(ROLE, retrievedContext);

        assertTrue(expandedAtLeastOnce, "small-to-big 未放大上下文（父块未大于子块）");
        assertTrue(PERIOD_2026.matcher(retrievedContext).find(),
                "回溯上下文缺少实习一 Mar 2026 - Present");
        assertTrue(PERIOD_2025.matcher(retrievedContext).find(),
                "回溯上下文缺少实习二 Mar 2025 - Aug 2025");
        assertEquals(2, roleCount,
                "回溯上下文应呈现 2 段 Full Stack Intern（即答案 = 2 段实习），实际: " + roleCount);

        System.out.println("✅ 父子分块回溯上下文包含 2 段实习（Full Stack Intern x" + roleCount + "）");
    }

    // ───────────── helpers ─────────────

    private static Map<Integer, String> distinctParents(List<ParentChildChunk> chunks) {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (ParentChildChunk c : chunks) {
            map.putIfAbsent(c.parentChunkId, c.parentContent);
        }
        return map;
    }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static String stripWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }
}
