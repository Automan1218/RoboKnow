package com.yizhaoqi.roboknow.client;

public record AiUsageMetadata(String username, String conversationId, String operation) {

    public static AiUsageMetadata system(String operation) {
        return new AiUsageMetadata("system", null, operation);
    }
}
