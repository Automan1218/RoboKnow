package com.yizhaoqi.roboknow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseServiceChunkOverlapTest {

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "chunkOverlapSize", 7);
    }

    @Test
    void semanticChunksCarryPreviousTailSentenceAsOverlap() throws Exception {
        String text = "Context before boundary. Tail.\n\nNext chunk holds the answer about Software Engineering.";

        Method method = ParseService.class.getDeclaredMethod("splitTextIntoChunksWithSemantics", String.class, int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) method.invoke(parseService, text, 70);

        assertEquals(2, chunks.size());
        assertEquals("Context before boundary. Tail.", chunks.get(0));
        assertTrue(chunks.get(1).startsWith("Tail.\n\nNext chunk holds the answer"));
    }
}
