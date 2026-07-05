package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.DocumentVector;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真·MySQL 持久化测试：验证父子分块的 parent_chunk_id / parent_content 两列
 * 通过真实 JPA 实体 + Repository 在真实 MySQL 上正确落库与读回（small-to-big 的存储侧）。
 *
 * @DataJpaTest 只装配 JPA 切片（不起 Kafka/ES/MinIO，无需 OpenAI）；
 * ddl-auto=update 会在真实库上自动补出两列；事务结束自动回滚，不污染 dev 库。
 *
 * ES 不可达或库连不上时由 Spring 抛错——本机 infra 已确认在线（mysql:3307）。
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false"
})
class ParentChildPersistenceIT {

    private static final String MD5 = "persistit000000000000000000000bb";

    @Autowired
    private DocumentVectorRepository repository;

    @Autowired
    private TestEntityManager em;

    /** 单条往返：parent_chunk_id 与 parent_content 真实落库后能原样读回。 */
    @Test
    void parentFields_roundTripThroughRealMysql() {
        DocumentVector v = new DocumentVector();
        v.setFileMd5(MD5);
        v.setChunkId(1);
        v.setTextContent("Full Stack Intern Mar 2026 - Present");
        v.setParentChunkId(7L);
        v.setParentContent("RoboAct Pte Ltd. Full Stack Intern Mar 2026 - Present. ... Full Stack Intern Mar 2025 - Aug 2025.");
        v.setUserId("admin");
        v.setOrgTag("default");
        v.setPublic(true);

        Long id = repository.save(v).getVectorId();
        em.flush();
        em.clear(); // 清一级缓存，强制从 MySQL 重新读

        DocumentVector loaded = repository.findById(id).orElseThrow();
        assertNotNull(loaded.getParentChunkId(), "parent_chunk_id 未落库");
        assertEquals(7L, loaded.getParentChunkId());
        assertTrue(loaded.getParentContent().contains("Mar 2026 - Present")
                && loaded.getParentContent().contains("Mar 2025 - Aug 2025"),
                "parent_content 未原样读回");
    }

    /**
     * 去重语义：多个子块共享同一 parent_chunk_id，按 fileMd5 查回后能正确归并到父块。
     * 这正是检索侧 small-to-big 回溯依赖的存储结构。
     */
    @Test
    void childrenShareParent_groupBackToParentsOnRealMysql() {
        // 父块 1：两段实习的完整上下文；其下 2 个子块
        String parent1 = "Full Stack Intern Mar 2026 - Present ... Full Stack Intern Mar 2025 - Aug 2025";
        repository.save(child(11, "knowledge management system intern", 1, parent1));
        repository.save(child(12, "microservices project intern", 1, parent1));
        // 父块 2：技能段；1 个子块
        repository.save(child(13, "Java Python skills", 2, "Skills: Java, Python"));
        em.flush();
        em.clear();

        List<DocumentVector> rows = repository.findByFileMd5(MD5);
        assertEquals(3, rows.size(), "应查回 3 个子块");

        Map<Long, String> parents = rows.stream()
                .collect(Collectors.toMap(
                        DocumentVector::getParentChunkId,
                        DocumentVector::getParentContent,
                        (a, b) -> a)); // 同父块取一份 → 去重
        assertEquals(2, parents.size(), "3 个子块应归并为 2 个父块");
        assertTrue(parents.get(1L).contains("Mar 2026 - Present")
                && parents.get(1L).contains("Mar 2025 - Aug 2025"),
                "父块 1 应含两段实习");
    }

    private DocumentVector child(int chunkId, String childText, int parentId, String parentText) {
        DocumentVector v = new DocumentVector();
        v.setFileMd5(MD5);
        v.setChunkId(chunkId);
        v.setTextContent(childText);
        v.setParentChunkId((long) parentId);
        v.setParentContent(parentText);
        v.setUserId("admin");
        v.setOrgTag("default");
        v.setPublic(true);
        return v;
    }
}
