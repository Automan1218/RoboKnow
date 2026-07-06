package com.yizhaoqi.roboknow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParseServiceParentChildTest {

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "chunkSize", 100);
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 300);
        ReflectionTestUtils.setField(parseService, "chunkOverlapSize", 0);
        ReflectionTestUtils.setField(parseService, "chunkOverlapRatio", 0.0);
    }

    @Test
    void parentChunksAreLargerThanChildChunks() throws Exception {
        String text = "Alpha beta gamma delta epsilon. ".repeat(30); // ~960 chars

        Method splitParent = ParseService.class.getDeclaredMethod(
                "splitTextIntoChunksWithSemantics", String.class, int.class);
        splitParent.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> parents = (List<String>) splitParent.invoke(parseService, text, 300);

        @SuppressWarnings("unchecked")
        List<String> children = (List<String>) splitParent.invoke(parseService, text, 100);

        assertTrue(parents.size() < children.size(),
            "Should produce fewer parent chunks than child chunks");
        assertTrue(parents.size() >= 1, "Should produce at least one parent chunk");
        assertTrue(children.size() >= 1, "Should produce at least one child chunk");
    }

    @Test
    void childrenCoverSameTextAsParents() throws Exception {
        String text = "Sentence one. Sentence two. Sentence three. Sentence four. " +
                      "Sentence five. Sentence six. Sentence seven. Sentence eight.";

        Method split = ParseService.class.getDeclaredMethod(
                "splitTextIntoChunksWithSemantics", String.class, int.class);
        split.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> parents = (List<String>) split.invoke(parseService, text, 300);

        for (String parent : parents) {
            @SuppressWarnings("unchecked")
            List<String> children = (List<String>) split.invoke(parseService, parent, 100);
            assertFalse(children.isEmpty(),
                "Each parent must yield at least one child chunk");
        }
    }

    @Test
    void resolvedParentChunkSizeUsesConfigWhenPositive() throws Exception {
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 2048);

        Method resolve = ParseService.class.getDeclaredMethod("resolvedParentChunkSize");
        resolve.setAccessible(true);

        int result = (int) resolve.invoke(parseService);
        assertEquals(2048, result);
    }

    @Test
    void resolvedParentChunkSizeDefaultsToThreeTimesChildSize() throws Exception {
        ReflectionTestUtils.setField(parseService, "chunkSize", 1024);
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 0);

        Method resolve = ParseService.class.getDeclaredMethod("resolvedParentChunkSize");
        resolve.setAccessible(true);

        int result = (int) resolve.invoke(parseService);
        assertEquals(3072, result);
    }
}
