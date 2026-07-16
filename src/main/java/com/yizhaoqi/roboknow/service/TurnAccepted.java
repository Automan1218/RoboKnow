package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationTurn;

public record TurnAccepted(int turnSeq, String requestId, ConversationTurn.Status status) {
}
