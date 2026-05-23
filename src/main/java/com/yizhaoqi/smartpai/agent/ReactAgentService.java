package com.yizhaoqi.smartpai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.tool.ToolRegistry;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.service.AgentStopService;
import com.yizhaoqi.smartpai.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct Agent with short-term memory compression and long-term memory injection.
 *
 * Short-term: Redis conversation history. When > STM_THRESHOLD messages, oldest messages
 * are compressed into a rolling summary stored at conversation:{id}:stm_summary.
 * Context sent to LLM = [system] + [STM summary?] + [LTM summaries?] + [last CONTEXT_WINDOW messages] + [user msg]
 *
 * Long-term: after each exchange a one-sentence summary is saved to DB via ConversationService.
 * On new messages the last LTM_LIMIT summaries are injected as system context.
 */
@Service
public class ReactAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ReactAgentService.class);
    private static final int MAX_ITERATIONS = 5;
    private static final int STM_THRESHOLD = 20;   // compress when history exceeds this
    private static final int CONTEXT_WINDOW = 10;  // recent messages sent to LLM
    private static final int LTM_LIMIT = 3;        // past conversation summaries to inject

    private static final Pattern THOUGHT_PATTERN =
        Pattern.compile("Thought:\\s*(.+?)(?=\\nAction:|\\nFinal Answer:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN =
        Pattern.compile("Action:\\s*(.+?)(?=\\nAction Input:|\\nThought:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN =
        Pattern.compile("Action Input:\\s*(.+?)(?=\\nObservation:|\\nThought:|\\nAction:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN =
        Pattern.compile("Final Answer:\\s*(.+?)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final DeepSeekClient deepSeekClient;
    private final ToolRegistry toolRegistry;
    private final AgentStopService agentStopService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReactAgentService(DeepSeekClient deepSeekClient,
                             ToolRegistry toolRegistry,
                             AgentStopService agentStopService,
                             RedisTemplate<String, String> redisTemplate,
                             ConversationService conversationService) {
        this.deepSeekClient = deepSeekClient;
        this.toolRegistry = toolRegistry;
        this.agentStopService = agentStopService;
        this.redisTemplate = redisTemplate;
        this.conversationService = conversationService;
    }

    // ─────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("ReactAgent processing message, user: {}", userId);
        try {
            String conversationId = getOrCreateConversationId(userId);
            List<Map<String, String>> history = getConversationHistory(conversationId);

            AgentContext ctx = new AgentContext(userId, userMessage, conversationId, history, session);
            String finalAnswer = runReActLoop(ctx);

            sendCompletionNotification(session);
            updateConversationHistory(conversationId, userId, userMessage, finalAnswer);
            logger.info("ReactAgent done, user: {}", userId);
        } catch (Exception e) {
            logger.error("ReactAgent failed: {}", e.getMessage(), e);
            sendError(session, "AI 服务暂时不可用，请稍后重试");
        } finally {
            agentStopService.clear(session.getId());
        }
    }

    // ─────────────────────────────────────────────────────────
    // ReAct loop
    // ─────────────────────────────────────────────────────────

    private String runReActLoop(AgentContext ctx) throws InterruptedException {
        List<Map<String, String>> messages = buildInitialMessages(ctx);
        String finalAnswer = null;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (agentStopService.shouldStop(ctx.getSession().getId())) {
                logger.info("Stop signal detected, breaking at iteration {}", i);
                break;
            }

            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.THINKING, i + 1));
            logger.debug("ReAct iteration {}: calling LLM", i + 1);

            String llmResponse = deepSeekClient.chatBlocking(messages);
            if (llmResponse.isBlank()) {
                logger.warn("LLM returned empty response at iteration {}", i + 1);
                break;
            }

            AgentStep step = parseResponse(llmResponse, i + 1);

            if (step.thought != null && !step.thought.isBlank()) {
                pushEvent(ctx.getSession(), AgentEvent.thought(step.thought));
            }

            if (step.isFinalAnswer) {
                finalAnswer = step.finalAnswer;
                break;
            }

            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ACTING, i + 1));
            pushEvent(ctx.getSession(), AgentEvent.action(step.action, step.actionInput));

            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.OBSERVING, i + 1));
            String observation = toolRegistry.execute(step.action, step.actionInput, ctx);
            pushEvent(ctx.getSession(), AgentEvent.observation(step.action, observation));
            logger.debug("Tool {} returned observation ({} chars)", step.action, observation.length());

            messages.add(Map.of("role", "assistant", "content", step.formatAssistantContent()));
            messages.add(Map.of("role", "user",      "content", "Observation: " + observation));
        }

        if (finalAnswer == null) {
            finalAnswer = "经过多轮检索，未找到足够相关信息来回答您的问题。" +
                          "请尝试换一种提问方式，或确认相关文档已上传到知识库。";
        }

        pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ANSWERING, 0));
        streamText(ctx.getSession(), finalAnswer);

        return finalAnswer;
    }

    // ─────────────────────────────────────────────────────────
    // Message construction with memory
    // ─────────────────────────────────────────────────────────

    private List<Map<String, String>> buildInitialMessages(AgentContext ctx) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));

        // Long-term memory: summaries of recent past conversations (cross-session)
        String ltmContext = loadLongTermContext(ctx.getUserId());
        if (ltmContext != null) {
            messages.add(Map.of("role", "system", "content", ltmContext));
        }

        // Short-term memory: compressed summary of older in-session messages
        String stmSummary = getShortTermSummary(ctx.getConversationId());
        if (stmSummary != null) {
            messages.add(Map.of("role", "system", "content",
                    "Summary of earlier conversation in this session:\n" + stmSummary));
        }

        // Recent history within context window
        List<Map<String, String>> history = ctx.getHistory();
        int start = Math.max(0, history.size() - CONTEXT_WINDOW);
        messages.addAll(history.subList(start, history.size()));

        messages.add(Map.of("role", "user", "content", ctx.getUserMessage()));
        return messages;
    }

    private String buildSystemPrompt() {
        return "You are an enterprise knowledge-base assistant. Use the available tools to answer the user's question.\n\n" +
               "**Available tools:**\n" +
               toolRegistry.getToolDescriptions() + "\n" +
               "**Response format (strictly follow this; answer in English):**\n\n" +
               "When a tool is needed:\n" +
               "Thought: [analyze the situation and decide the next action]\n" +
               "Action: [tool name, must be one of the tools listed above]\n" +
               "Action Input: [tool input]\n\n" +
               "When enough information is available:\n" +
               "Thought: [final reasoning]\n" +
               "Final Answer: [complete answer to the user in English]\n\n" +
               "**Rules:**\n" +
               "- Answer only in English.\n" +
               "- Use at most one tool per step.\n" +
               "- Always write Thought first, then Action or Final Answer.\n" +
               "- Tool results are returned as Observation: ...\n" +
               "- Base the answer on actual knowledge-base content; do not fabricate information.\n" +
               "- If repeated searches find no relevant information, clearly say so in English.\n";
    }

    // ─────────────────────────────────────────────────────────
    // Long-term memory (DB-backed, cross-session)
    // ─────────────────────────────────────────────────────────

    private String loadLongTermContext(String username) {
        try {
            List<String> summaries = conversationService.getRecentSummaries(username, LTM_LIMIT);
            if (summaries.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("Previous conversation topics (for context only):\n");
            for (String s : summaries) {
                sb.append("- ").append(s).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to load long-term memory for user {}: {}", username, e.getMessage());
            return null;
        }
    }

    private static final List<String> EMPTY_ANSWER_SIGNALS = List.of(
        "未找到", "找不到", "无相关", "没有找到", "无法找到", "no relevant", "not found", "no information"
    );

    private boolean isWorthSavingToLtm(String question, String answer) {
        String combined = question + answer;
        if (combined.length() < 50) return false;
        String lowerAnswer = answer.toLowerCase();
        for (String signal : EMPTY_ANSWER_SIGNALS) {
            if (lowerAnswer.contains(signal.toLowerCase())) return false;
        }
        return true;
    }

    private void saveToLongTermMemory(String username, String question, String answer) {
        if (!isWorthSavingToLtm(question, answer)) {
            logger.debug("跳过 LTM 写入：内容无实质性结论，用户: {}", username);
            return;
        }
        try {
            String snippet = answer.length() > 500 ? answer.substring(0, 500) : answer;
            List<Map<String, String>> req = List.of(
                Map.of("role", "system", "content",
                       "从以下问答中提取已确认的关键结论、事实或用户获得的重要信息，用一句话概括。" +
                       "只提取明确、具体的结论，不要概括问题本身，不要写'用户问了...'。"),
                Map.of("role", "user", "content",
                       "问题：" + question + "\n回答：" + snippet)
            );
            String summary = deepSeekClient.chatBlocking(req);
            if (summary == null || summary.isBlank()) {
                summary = question.length() > 120 ? question.substring(0, 120) + "..." : question;
            }
            conversationService.recordConversation(username, question, answer, summary);
            logger.debug("LTM 写入成功，用户: {}，结论: {}", username, summary);
        } catch (Exception e) {
            logger.warn("LTM 写入失败，用户: {}: {}", username, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // Short-term memory (Redis, in-session compression)
    // ─────────────────────────────────────────────────────────

    private String getShortTermSummary(String conversationId) {
        try {
            return redisTemplate.opsForValue().get(stmSummaryKey(conversationId));
        } catch (Exception e) {
            logger.warn("Failed to read STM summary: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Summarizes messages[0..size-CONTEXT_WINDOW] into a rolling summary,
     * persists it to Redis, and returns only the recent tail.
     */
    private List<Map<String, String>> compressShortTermMemory(String conversationId,
                                                               List<Map<String, String>> history) {
        int splitAt = history.size() - CONTEXT_WINDOW;
        List<Map<String, String>> toCompress = history.subList(0, splitAt);
        List<Map<String, String>> recent = new ArrayList<>(history.subList(splitAt, history.size()));

        try {
            String existing = getShortTermSummary(conversationId);
            StringBuilder input = new StringBuilder();
            if (existing != null && !existing.isBlank()) {
                input.append("Previous summary:\n").append(existing).append("\n\nNew messages to incorporate:\n");
            }
            for (Map<String, String> msg : toCompress) {
                input.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
            }

            List<Map<String, String>> req = List.of(
                Map.of("role", "system", "content",
                       "Summarize the following conversation history in 3-5 sentences, preserving key facts and context needed to understand the ongoing conversation."),
                Map.of("role", "user", "content", input.toString())
            );
            String newSummary = deepSeekClient.chatBlocking(req);
            if (newSummary != null && !newSummary.isBlank()) {
                redisTemplate.opsForValue().set(stmSummaryKey(conversationId), newSummary, Duration.ofDays(7));
                logger.debug("STM compressed {} messages into summary for conversation {}", toCompress.size(), conversationId);
            }
        } catch (Exception e) {
            logger.warn("STM compression failed for conversation {}: {}", conversationId, e.getMessage());
        }
        return recent;
    }

    private String stmSummaryKey(String conversationId) {
        return "conversation:" + conversationId + ":stm_summary";
    }

    // ─────────────────────────────────────────────────────────
    // Conversation history (Redis)
    // ─────────────────────────────────────────────────────────

    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
        }
        return conversationId;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        try {
            if (json == null) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            logger.error("Failed to read conversation history, conversationId={}: {}", conversationId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userId,
                                           String userMessage, String response) {
        String key = "conversation:" + conversationId;
        List<Map<String, String>> history = getConversationHistory(conversationId);

        String ts = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        userMsg.put("timestamp", ts);
        history.add(userMsg);

        Map<String, String> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", response);
        assistantMsg.put("timestamp", ts);
        history.add(assistantMsg);

        // Compress older messages instead of dropping them
        if (history.size() > STM_THRESHOLD) {
            history = compressShortTermMemory(conversationId, history);
        }

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), Duration.ofDays(7));
        } catch (Exception e) {
            logger.error("Failed to update conversation history, conversationId={}: {}", conversationId, e.getMessage());
        }

        // Save to DB asynchronously (long-term memory)
        CompletableFuture.runAsync(() -> saveToLongTermMemory(userId, userMessage, response));
    }

    // ─────────────────────────────────────────────────────────
    // LLM response parsing
    // ─────────────────────────────────────────────────────────

    private AgentStep parseResponse(String response, int iteration) {
        AgentStep step = new AgentStep(iteration);

        Matcher faMatcher = FINAL_ANSWER_PATTERN.matcher(response);
        if (faMatcher.find()) {
            step.isFinalAnswer = true;
            step.finalAnswer = faMatcher.group(1).trim();
            Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
            if (thoughtMatcher.find()) {
                step.thought = thoughtMatcher.group(1).trim();
            }
            return step;
        }

        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
        if (thoughtMatcher.find()) {
            step.thought = thoughtMatcher.group(1).trim();
        }

        Matcher actionMatcher = ACTION_PATTERN.matcher(response);
        if (actionMatcher.find()) {
            step.action = actionMatcher.group(1).trim();
        }

        Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(response);
        if (inputMatcher.find()) {
            step.actionInput = inputMatcher.group(1).trim();
        }

        if (step.action == null || step.actionInput == null || !toolRegistry.hasTool(step.action)) {
            logger.warn("No valid tool call parsed, treating LLM response as final answer at iteration {}", iteration);
            step.isFinalAnswer = true;
            step.finalAnswer = response.trim();
        }

        return step;
    }

    // ─────────────────────────────────────────────────────────
    // WebSocket push
    // ─────────────────────────────────────────────────────────

    private void pushEvent(WebSocketSession session, AgentEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event.getPayload());
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            logger.error("Failed to push agent event: {}", e.getMessage(), e);
        }
    }

    private void streamText(WebSocketSession session, String text) throws InterruptedException {
        int chunkSize = 30;
        for (int i = 0; i < text.length(); i += chunkSize) {
            if (agentStopService.shouldStop(session.getId())) break;

            String chunk = text.substring(i, Math.min(i + chunkSize, text.length()));
            try {
                Map<String, String> payload = Map.of("chunk", chunk);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (Exception e) {
                logger.error("Failed to stream text chunk: {}", e.getMessage(), e);
                break;
            }
            if (i + chunkSize < text.length()) {
                Thread.sleep(25);
            }
        }
    }

    private void sendCompletionNotification(WebSocketSession session) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "completion");
            notification.put("status", "finished");
            notification.put("message", "响应已完成");
            notification.put("timestamp", System.currentTimeMillis());
            notification.put("date", java.time.LocalDateTime.now().toString());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("Failed to send completion notification: {}", e.getMessage(), e);
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            Map<String, String> err = Map.of("error", message);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(err)));
        } catch (Exception e) {
            logger.error("Failed to send error message: {}", e.getMessage(), e);
        }
    }
}
