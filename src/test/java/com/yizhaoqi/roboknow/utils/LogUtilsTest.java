package com.yizhaoqi.roboknow.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class LogUtilsTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void logMethodsClearMappedDiagnosticContext() {
        LogUtils.logBusiness("CREATE", "user-1", "created %s", "doc");
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());

        LogUtils.logBusinessError("CREATE", "user-1", "failed %s",
                new IllegalArgumentException("bad"), "doc");
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());

        LogUtils.logPerformance("UPLOAD", 12, "small file");
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());

        LogUtils.logUserOperation("user-1", "DELETE", "document", "denied");
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());

        LogUtils.logApiCall("GET", "/api/docs", "user-1", 200, 7);
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());

        LogUtils.logFileOperation("user-1", "UPLOAD", "file.pdf", "abc", "ok");
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());

        LogUtils.logChat("user-1", "session-1", "user", 20);
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }

    @Test
    void requestContextCanBeSetAndCleared() {
        LogUtils.setRequestContext("request-1", "user-1", "session-1");

        assertEquals("request-1", MDC.get(LogUtils.REQUEST_ID));
        assertEquals("user-1", MDC.get(LogUtils.USER_ID));
        assertEquals("session-1", MDC.get(LogUtils.SESSION_ID));

        LogUtils.clearRequestContext();

        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }

    @Test
    void requestContextSkipsNullOptionalValues() {
        LogUtils.setRequestContext("request-1", null, null);

        assertEquals("request-1", MDC.get(LogUtils.REQUEST_ID));
        assertNull(MDC.get(LogUtils.USER_ID));
        assertNull(MDC.get(LogUtils.SESSION_ID));
    }

    @Test
    void systemLogsAndPerformanceMonitorDoNotThrow() {
        assertDoesNotThrow(() -> LogUtils.logSystemStart("backend", "started", "ok"));
        assertDoesNotThrow(() -> LogUtils.logSystemError("backend", "failed", new RuntimeException("x")));

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("parse");

        assertDoesNotThrow(() -> monitor.end());
        assertDoesNotThrow(() -> monitor.end("details"));
    }

    @Test
    void invalidFormatStringFallsBackToOriginalMessage() {
        assertDoesNotThrow(() -> LogUtils.logBusiness("FORMAT", "user-1", "%d", "not a number"));
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }
}
