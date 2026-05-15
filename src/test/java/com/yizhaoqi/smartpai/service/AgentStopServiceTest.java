package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentStopServiceTest {

    @Test
    void requestStopSetsFlagAndClearRemovesIt() {
        AgentStopService service = new AgentStopService();

        assertFalse(service.shouldStop("session-1"));

        service.requestStop("session-1");

        assertTrue(service.shouldStop("session-1"));
        assertFalse(service.shouldStop("session-2"));

        service.clear("session-1");

        assertFalse(service.shouldStop("session-1"));
    }
}
