package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false"
})
class ConversationTurnRepositoryIT {

    @Autowired
    private ConversationTurnRepository repository;

    private ConversationTurn build(String convId, int turnSeq, String requestId) {
        ConversationTurn t = new ConversationTurn();
        t.setConvId(convId);
        t.setTurnSeq(turnSeq);
        t.setRequestId(requestId);
        t.setUserContent("hello");
        t.setReceivedAt(LocalDateTime.now());
        return t;
    }

    @Test
    void duplicateTurnSeqForSameConvRejected() {
        repository.saveAndFlush(build("it-turn-conv-1", 1, "req-1"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(build("it-turn-conv-1", 1, "req-2")));
    }

    @Test
    void duplicateRequestIdForSameConvRejected() {
        repository.saveAndFlush(build("it-turn-conv-2", 1, "dup-req"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(build("it-turn-conv-2", 2, "dup-req")));
    }

    @Test
    void claimForProcessingOnlySucceedsFromPending() {
        ConversationTurn t = repository.saveAndFlush(build("it-turn-conv-3", 1, "req-3"));

        int firstClaim = repository.claimForProcessing(t.getId(), "token-a");
        assertEquals(1, firstClaim);

        int secondClaim = repository.claimForProcessing(t.getId(), "token-b");
        assertEquals(0, secondClaim, "already PROCESSING turn must not be claimable again");
    }

    @Test
    void completeIfOwnedRejectsWrongAttemptToken() {
        ConversationTurn t = repository.saveAndFlush(build("it-turn-conv-4", 1, "req-4"));
        repository.claimForProcessing(t.getId(), "token-real");

        int wrongTokenResult = repository.completeIfOwned(t.getId(), "token-fake", "answer");
        assertEquals(0, wrongTokenResult);

        int rightTokenResult = repository.completeIfOwned(t.getId(), "token-real", "answer");
        assertEquals(1, rightTokenResult);
    }

    @Test
    void findFirstByConvIdAndStatusOrderByTurnSeqAscReturnsEarliestPending() {
        repository.saveAndFlush(build("it-turn-conv-5", 3, "req-5c"));
        repository.saveAndFlush(build("it-turn-conv-5", 1, "req-5a"));
        repository.saveAndFlush(build("it-turn-conv-5", 2, "req-5b"));

        var earliest = repository.findFirstByConvIdAndStatusOrderByTurnSeqAsc(
                "it-turn-conv-5", ConversationTurn.Status.PENDING);
        assertTrue(earliest.isPresent());
        assertEquals(1, earliest.get().getTurnSeq());
    }
}
