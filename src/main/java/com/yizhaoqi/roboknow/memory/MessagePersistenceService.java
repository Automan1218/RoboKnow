package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MessagePersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(MessagePersistenceService.class);
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ConversationMessageRepository repository;

    public MessagePersistenceService(ConversationMessageRepository repository) {
        this.repository = repository;
    }

    /** Durable write-behind: persists the user+assistant pair. Never blocks the caller. */
    @Async("memoryExecutor")
    public void saveAsync(String convId, String question, String answer) {
        try {
            int nextSeq = (int) repository.countByConvId(convId);
            LocalDateTime now = LocalDateTime.now();

            ConversationMessage userMsg = new ConversationMessage();
            userMsg.setConvId(convId);
            userMsg.setSeq(nextSeq);
            userMsg.setRole("user");
            userMsg.setContent(question);
            userMsg.setCreatedAt(now);
            repository.save(userMsg);

            ConversationMessage assistantMsg = new ConversationMessage();
            assistantMsg.setConvId(convId);
            assistantMsg.setSeq(nextSeq + 1);
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(answer);
            assistantMsg.setCreatedAt(now);
            repository.save(assistantMsg);
        } catch (Exception e) {
            logger.error("Failed to persist message pair for convId={}: {}", convId, e.getMessage());
        }
    }

    /** Cache-aside DB read. Returns empty list on miss or failure — never throws. */
    public List<Map<String, String>> loadFromDb(String convId) {
        try {
            List<ConversationMessage> rows = repository.findByConvIdOrderBySeqAsc(convId);
            List<Map<String, String>> result = new ArrayList<>();
            for (ConversationMessage row : rows) {
                Map<String, String> m = new HashMap<>();
                m.put("role", row.getRole());
                m.put("content", row.getContent());
                m.put("timestamp", row.getCreatedAt().format(TS_FMT));
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to load messages from DB for convId={}: {}", convId, e.getMessage());
            return new ArrayList<>();
        }
    }
}
