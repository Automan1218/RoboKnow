package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false"
})
class ConversationMessageUniqueSeqIT {

    @Autowired
    private ConversationMessageRepository repository;

    @Test
    void duplicateConvIdSeqRejected() {
        ConversationMessage m1 = new ConversationMessage();
        m1.setConvId("it-uniqseq-conv-1");
        m1.setSeq(0);
        m1.setRole("user");
        m1.setContent("hi");
        m1.setCreatedAt(LocalDateTime.now());
        repository.saveAndFlush(m1);

        ConversationMessage m2 = new ConversationMessage();
        m2.setConvId("it-uniqseq-conv-1");
        m2.setSeq(0);
        m2.setRole("assistant");
        m2.setContent("hi back");
        m2.setCreatedAt(LocalDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(m2));
    }
}
