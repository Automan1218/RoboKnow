package com.yizhaoqi.roboknow.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.roboknow.config.AiProperties;
import com.yizhaoqi.roboknow.service.AiTokenUsageService;

@Service
public class OpenAiClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private final AiTokenUsageService aiTokenUsageService;
    private static final Logger logger = LoggerFactory.getLogger(OpenAiClient.class);

    public OpenAiClient(@Value("${openai.api.url}") String apiUrl,
                         @Value("${openai.api.key}") String apiKey,
                         @Value("${openai.api.model}") String model,
                         AiProperties aiProperties,
                         AiTokenUsageService aiTokenUsageService) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);

        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
        this.aiTokenUsageService = aiTokenUsageService;
    }

    public void streamResponse(String userMessage,
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError) {

        Map<String, Object> request = buildRequest(userMessage, context, history);

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processChunk(chunk, onChunk),
                    onError
                );
    }

    private Map<String, Object> buildRequest(String userMessage,
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}",
                   userMessage,
                   context != null ? context.length() : 0,
                   history != null ? history.size() : 0);

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }

    /**
     * 同步阻塞调用（非流式），用于 ReAct 中间推理步骤。
     * 使用 stream:false，直接返回完整响应文本。
     */
    public String chatBlocking(List<Map<String, String>> messages) {
        return chatBlocking(messages, AiUsageMetadata.system("chat_blocking"));
    }

    public String chatBlocking(List<Map<String, String>> messages, AiUsageMetadata usageMetadata) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) request.put("temperature", gen.getTemperature());
        if (gen.getTopP() != null)         request.put("top_p", gen.getTopP());
        if (gen.getMaxTokens() != null)    request.put("max_tokens", gen.getMaxTokens());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(60));

            if (response == null) return "";
            recordUsage(response, usageMetadata);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) return "";

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return "";

            Object content = message.get("content");
            return content == null ? "" : content.toString();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            logger.error("chatBlocking 调用失败: {} | body: {}", e.getMessage(), e.getResponseBodyAsString());
            return "";
        } catch (Exception e) {
            logger.error("chatBlocking 调用失败: {}", e.getMessage(), e);
            return "";
        }
    }

    private void recordUsage(Map<String, Object> response, AiUsageMetadata usageMetadata) {
        Object usage = response.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) {
            return;
        }

        Map<String, Object> normalizedUsage = new HashMap<>();
        usageMap.forEach((key, value) -> normalizedUsage.put(String.valueOf(key), value));
        aiTokenUsageService.recordUsage(usageMetadata, model, normalizedUsage);
    }

    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }

            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                               .path(0)
                               .path("delta")
                               .path("content")
                               .asText("");

            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }
}
