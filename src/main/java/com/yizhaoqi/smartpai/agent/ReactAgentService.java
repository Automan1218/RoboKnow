package com.yizhaoqi.smartpai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.tool.ToolRegistry;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.service.AgentStopService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct Agent 核心服务。
 *
 * 状态机流转：THINKING → ACTING → OBSERVING → THINKING（循环，最多 MAX_ITERATIONS 轮）→ ANSWERING
 *
 * 每个状态变化通过 WebSocket 推送结构化事件，前端可实时渲染完整推理链。
 */
@Service
public class ReactAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ReactAgentService.class);
    private static final int MAX_ITERATIONS = 5;

    // ReAct 响应解析正则
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReactAgentService(DeepSeekClient deepSeekClient,
                             ToolRegistry toolRegistry,
                             AgentStopService agentStopService,
                             RedisTemplate<String, String> redisTemplate) {
        this.deepSeekClient = deepSeekClient;
        this.toolRegistry = toolRegistry;
        this.agentStopService = agentStopService;
        this.redisTemplate = redisTemplate;
    }

    // ─────────────────────────────────────────────────────────
    // 入口：由 ChatHandler 在独立线程中调用
    // ─────────────────────────────────────────────────────────

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("ReactAgent 开始处理消息，用户: {}", userId);
        try {
            String conversationId = getOrCreateConversationId(userId);
            List<Map<String, String>> history = getConversationHistory(conversationId);

            AgentContext ctx = new AgentContext(userId, userMessage, conversationId, history, session);
            String finalAnswer = runReActLoop(ctx);

            sendCompletionNotification(session);
            updateConversationHistory(conversationId, userMessage, finalAnswer);
            logger.info("ReactAgent 处理完成，用户: {}", userId);
        } catch (Exception e) {
            logger.error("ReactAgent 处理消息失败: {}", e.getMessage(), e);
            sendError(session, "AI 服务暂时不可用，请稍后重试");
        } finally {
            agentStopService.clear(session.getId());
        }
    }

    // ─────────────────────────────────────────────────────────
    // ReAct 主循环
    // ─────────────────────────────────────────────────────────

    private String runReActLoop(AgentContext ctx) throws InterruptedException {
        List<Map<String, String>> messages = buildInitialMessages(ctx);
        String finalAnswer = null;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (agentStopService.shouldStop(ctx.getSession().getId())) {
                logger.info("检测到停止信号，中断 ReAct 循环，迭代: {}", i);
                break;
            }

            // ── THINKING ──
            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.THINKING, i + 1));
            logger.debug("ReAct 迭代 {}：调用 LLM", i + 1);

            String llmResponse = deepSeekClient.chatBlocking(messages);
            if (llmResponse.isBlank()) {
                logger.warn("LLM 返回空响应，迭代: {}", i + 1);
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

            // ── ACTING ──
            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ACTING, i + 1));
            pushEvent(ctx.getSession(), AgentEvent.action(step.action, step.actionInput));

            // ── OBSERVING ──
            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.OBSERVING, i + 1));
            String observation = toolRegistry.execute(step.action, step.actionInput, ctx);
            pushEvent(ctx.getSession(), AgentEvent.observation(step.action, observation));
            logger.debug("工具 {} 返回 Observation ({}字符)", step.action, observation.length());

            // 追加本轮对话到消息历史
            messages.add(Map.of("role", "assistant", "content", step.formatAssistantContent()));
            messages.add(Map.of("role", "user",      "content", "Observation: " + observation));
        }

        // 超过最大迭代次数仍无 Final Answer
        if (finalAnswer == null) {
            finalAnswer = "经过多轮检索，未找到足够相关信息来回答您的问题。" +
                          "请尝试换一种提问方式，或确认相关文档已上传到知识库。";
        }

        // ── ANSWERING ──
        pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ANSWERING, 0));
        streamText(ctx.getSession(), finalAnswer);

        return finalAnswer;
    }

    // ─────────────────────────────────────────────────────────
    // 消息构建
    // ─────────────────────────────────────────────────────────

    private List<Map<String, String>> buildInitialMessages(AgentContext ctx) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));

        // 保留最近 10 条历史，避免超出上下文窗口
        List<Map<String, String>> history = ctx.getHistory();
        int start = Math.max(0, history.size() - 10);
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
    // LLM 响应解析
    // ─────────────────────────────────────────────────────────

    private AgentStep parseResponse(String response, int iteration) {
        AgentStep step = new AgentStep(iteration);

        // 优先匹配 Final Answer
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

        // 提取 Thought
        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
        if (thoughtMatcher.find()) {
            step.thought = thoughtMatcher.group(1).trim();
        }

        // 提取 Action
        Matcher actionMatcher = ACTION_PATTERN.matcher(response);
        if (actionMatcher.find()) {
            step.action = actionMatcher.group(1).trim();
        }

        // 提取 Action Input
        Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(response);
        if (inputMatcher.find()) {
            step.actionInput = inputMatcher.group(1).trim();
        }

        // 未能解析出合法工具调用 → 视为最终答案
        if (step.action == null || step.actionInput == null || !toolRegistry.hasTool(step.action)) {
            logger.warn("未解析出合法工具调用，将 LLM 响应作为最终答案，迭代: {}", iteration);
            step.isFinalAnswer = true;
            step.finalAnswer = response.trim();
        }

        return step;
    }

    // ─────────────────────────────────────────────────────────
    // WebSocket 推送
    // ─────────────────────────────────────────────────────────

    private void pushEvent(WebSocketSession session, AgentEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event.getPayload());
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            logger.error("推送 Agent 事件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 将最终答案文本模拟流式推送，30 字符/批，给前端打字效果。
     */
    private void streamText(WebSocketSession session, String text) throws InterruptedException {
        int chunkSize = 30;
        for (int i = 0; i < text.length(); i += chunkSize) {
            if (agentStopService.shouldStop(session.getId())) break;

            String chunk = text.substring(i, Math.min(i + chunkSize, text.length()));
            try {
                Map<String, String> payload = Map.of("chunk", chunk);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (Exception e) {
                logger.error("流式推送文本块失败: {}", e.getMessage(), e);
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
            logger.error("发送完成通知失败: {}", e.getMessage(), e);
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            Map<String, String> err = Map.of("error", message);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(err)));
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // 对话历史（Redis，与 ChatHandler 逻辑一致）
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
            logger.error("读取对话历史失败, conversationId={}: {}", conversationId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response) {
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

        if (history.size() > 20) {
            history = history.subList(history.size() - 20, history.size());
        }

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), Duration.ofDays(7));
        } catch (Exception e) {
            logger.error("更新对话历史失败, conversationId={}: {}", conversationId, e.getMessage());
        }
    }
}
