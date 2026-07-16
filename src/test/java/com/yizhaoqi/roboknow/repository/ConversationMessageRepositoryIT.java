package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真·MySQL 持久化测试：ddl-auto=update 在真实库上自动建 conversation_messages 表；
 * 事务结束自动回滚，不污染 dev 库。
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false"
})
class ConversationMessageRepositoryIT {

    @Autowired
    private ConversationMessageRepository repository;

    private ConversationMessage build(String convId, int seq, String role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setConvId(convId);
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    @Test
    void findByConvIdOrderBySeqAscReturnsInOrder() {
        repository.save(build("it-conv-1", 1, "assistant", "hi back"));
        repository.save(build("it-conv-1", 0, "user", "hi"));
        repository.save(build("it-conv-2", 0, "user", "other conv"));

        List<ConversationMessage> result = repository.findByConvIdOrderBySeqAsc("it-conv-1");

        assertEquals(2, result.size());
        assertEquals(0, result.get(0).getSeq());
        assertEquals("user", result.get(0).getRole());
        assertEquals(1, result.get(1).getSeq());
    }

    @Test
    void countByConvIdCountsOnlyMatchingConversation() {
        repository.save(build("it-conv-3", 0, "user", "hi"));
        repository.save(build("it-conv-3", 1, "assistant", "hi back"));
        repository.save(build("it-conv-4", 0, "user", "other"));

        assertEquals(2, repository.countByConvId("it-conv-3"));
        assertEquals(1, repository.countByConvId("it-conv-4"));
    }
}
