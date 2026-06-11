package com.yizhaoqi.roboknow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.agent.tool.ToolRegistry;
import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import com.yizhaoqi.roboknow.client.OpenAiClient;
import com.yizhaoqi.roboknow.memory.MemoryManager;
import com.yizhaoqi.roboknow.service.AgentStopService;
import com.yizhaoqi.roboknow.service.SessionManager;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReactAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ReactAgentService.class);
    private static final int MAX_ITERATIONS = 5;

    private static final Pattern THOUGHT_PATTERN =
        Pattern.compile("Thought:\\s*(.+?)(?=\\nAction:|\\nFinal Answer:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN =
        Pattern.compile("Action:\\s*(.+?)(?=\\nAction Input:|\\nThought:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN =
        Pattern.compile("Action Input:\\s*(.+?)(?=\\nObservation:|\\nThought:|\\nAction:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN =
        Pattern.compile("Final Answer:\\s*(.+?)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final OpenAiClient openAiClient;
    private final ToolRegistry toolRegistry;
    private final AnswerGroundingService answerGroundingService;
    private final AgentStopService agentStopService;
    private final MemoryManager memoryManager;
    private final SessionManager sessionManager;
    private final ConversationSessionRepository sessionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReactAgentService(OpenAiClient openAiClient,
                              ToolRegistry toolRegistry,
                              AnswerGroundingService answerGroundingService,
                              AgentStopService agentStopService,
                              MemoryManager memoryManager,
                              SessionManager sessionManager,
                              ConversationSessionRepository sessionRepository) {
        this.openAiClient = openAiClient;
        this.toolRegistry = toolRegistry;
        this.answerGroundingService = answerGroundingService;
        this.agentStopService = agentStopService;
        this.memoryManager = memoryManager;
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
    }

    public void processMessage(String userId, String convId, String userMessage, WebSocketSession session) {
        logger.info("ReactAgent processing message, user: {}, convId: {}", userId, convId);
        try {
            List<Map<String, String>> contextMessages =
                    memoryManager.loadContext(userId, convId, userMessage);

            AgentContext ctx = new AgentContext(userId, userMessage, convId,
                    new ArrayList<>(), session);

            String finalAnswer = runReActLoop(ctx, contextMessages);

            sendCompletionNotification(session);
            memoryManager.record(userId, convId, userMessage, finalAnswer);
            // Generate title on first message (title still default)
            sessionRepository.findById(convId).ifPresent(s -> {
                if ("New conversation".equals(s.getTitle())) {
                    sessionManager.generateTitleAsync(convId, userMessage);
                }
            });
            logger.info("ReactAgent done, user: {}, convId: {}", userId, convId);
        } catch (Exception e) {
            logger.error("ReactAgent failed: {}", e.getMessage(), e);
            sendError(session, "The AI service is temporarily unavailable. Please try again later.");
        } finally {
            agentStopService.clear(session.getId());
        }
    }

    // ─────────────────────────────────────────────────────────
    // ReAct loop
    // ─────────────────────────────────────────────────────────

    private String runReActLoop(AgentContext ctx, List<Map<String, String>> contextMessages)
            throws InterruptedException {
        List<Map<String, String>> messages = buildInitialMessages(ctx, contextMessages);
        List<String> observations = new ArrayList<>();
        String finalAnswer = null;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (agentStopService.shouldStop(ctx.getSession().getId())) {
                logger.info("Stop signal detected, breaking at iteration {}", i);
                break;
            }

            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.THINKING, i + 1));
            logger.debug("ReAct iteration {}: calling LLM", i + 1);

            String llmResponse = openAiClient.chatBlocking(
                messages,
                new AiUsageMetadata(ctx.getUserId(), ctx.getConversationId(), "react_step")
            );
            if (llmResponse.isBlank()) {
                logger.warn("LLM returned empty response at iteration {}", i + 1);
                break;
            }

            AgentStep step = parseResponse(llmResponse, i + 1);

            if (step.thought != null && !step.thought.isBlank()) {
                pushEvent(ctx.getSession(), AgentEvent.thought(step.thought));
            }

            if (step.isFinalAnswer) {
                if (observations.isEmpty()) {
                    logger.warn("LLM skipped tool call and gave direct Final Answer — forcing hybrid_search");
                    pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ACTING, i + 1));
                    pushEvent(ctx.getSession(), AgentEvent.action("hybrid_search", ctx.getUserMessage()));
                    pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.OBSERVING, i + 1));
                    String forcedObs = toolRegistry.execute("hybrid_search", ctx.getUserMessage(), ctx);
                    observations.add(forcedObs);
                    pushEvent(ctx.getSession(), AgentEvent.observation("hybrid_search", forcedObs));
                    messages.add(Map.of("role", "assistant", "content", step.formatAssistantContent()));
                    messages.add(Map.of("role", "user", "content", "Observation: " + forcedObs));
                    continue;
                }
                finalAnswer = step.finalAnswer;
                break;
            }

            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ACTING, i + 1));
            pushEvent(ctx.getSession(), AgentEvent.action(step.action, step.actionInput));
            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.OBSERVING, i + 1));
            String observation = toolRegistry.execute(step.action, step.actionInput, ctx);
            observations.add(observation);
            pushEvent(ctx.getSession(), AgentEvent.observation(step.action, observation));
            logger.debug("Tool {} returned observation ({} chars)", step.action, observation.length());

            messages.add(Map.of("role", "assistant", "content", step.formatAssistantContent()));
            messages.add(Map.of("role", "user", "content", "Observation: " + observation));
        }

        if (finalAnswer == null) {
            finalAnswer = "No relevant information available. Repeated searches did not return enough knowledge-base evidence to answer this question.";
        }

        finalAnswer = answerGroundingService.groundAnswer(
            ctx.getUserMessage(), finalAnswer, observations,
            new AiUsageMetadata(ctx.getUserId(), ctx.getConversationId(), "answer_grounding")
        );

        pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ANSWERING, 0));
        streamText(ctx.getSession(), finalAnswer);
        return finalAnswer;
    }

    private List<Map<String, String>> buildInitialMessages(AgentContext ctx,
                                                             List<Map<String, String>> contextMessages) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        messages.addAll(contextMessages); // LTM facts + STM summary + recent history from MemoryManager
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
               "- MANDATORY: You MUST call hybrid_search at least once before giving any Final Answer. Never answer directly from conversation history or memory alone.\n" +
               "- Base the answer on retrieved knowledge-base content; do not fabricate information.\n" +
               "- Cite retrieved sources using the Source # markers from the observations.\n" +
               "- If searches find no relevant information, clearly say so in English.\n";
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
            if (thoughtMatcher.find()) step.thought = thoughtMatcher.group(1).trim();
            return step;
        }

        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
        if (thoughtMatcher.find()) step.thought = thoughtMatcher.group(1).trim();

        Matcher actionMatcher = ACTION_PATTERN.matcher(response);
        if (actionMatcher.find()) step.action = actionMatcher.group(1).trim();

        Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(response);
        if (inputMatcher.find()) step.actionInput = inputMatcher.group(1).trim();

        if (step.action == null || step.actionInput == null || !toolRegistry.hasTool(step.action)) {
            logger.warn("No valid tool call parsed, treating LLM response as final answer at iteration {}", iteration);
            step.isFinalAnswer = true;
            step.finalAnswer = response.trim();
        }
        return step;
    }

    // ─────────────────────────────────────────────────────────
    // WebSocket push helpers
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
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("chunk", chunk))));
            } catch (Exception e) {
                logger.error("Failed to stream text chunk: {}", e.getMessage(), e);
                break;
            }
            if (i + chunkSize < text.length()) Thread.sleep(25);
        }
    }

    private void sendCompletionNotification(WebSocketSession session) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "completion");
            notification.put("status", "finished");
            notification.put("message", "Response completed");
            notification.put("timestamp", System.currentTimeMillis());
            notification.put("date", java.time.LocalDateTime.now().toString());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("Failed to send completion notification: {}", e.getMessage(), e);
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("error", message))));
        } catch (Exception e) {
            logger.error("Failed to send error message: {}", e.getMessage(), e);
        }
    }
}
