package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.entity.SearchResult;
import com.yizhaoqi.roboknow.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchControllerTest {

    private SearchController controller;
    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        controller = new SearchController();
        hybridSearchService = mock(HybridSearchService.class);
        ReflectionTestUtils.setField(controller, "hybridSearchService", hybridSearchService);
    }

    @Test
    void hybridSearchWithUserIdUsesPermissionSearch() {
        SearchResult result = new SearchResult("md5-1", 1, "chunk text", 0.92);
        when(hybridSearchService.searchWithPermission("AI趋势", "user-1", 10))
                .thenReturn(List.of(result));

        Map<String, Object> response = controller.hybridSearch("AI趋势", 10, "user-1");

        assertEquals(200, response.get("code"));
        @SuppressWarnings("unchecked")
        List<?> data = (List<?>) response.get("data");
        assertEquals(1, data.size());
        verify(hybridSearchService).searchWithPermission("AI趋势", "user-1", 10);
        verify(hybridSearchService, never()).search(any(), anyInt());
    }

    @Test
    void hybridSearchWithoutUserIdUsesPublicSearch() {
        when(hybridSearchService.search("keyword", 5)).thenReturn(List.of());

        Map<String, Object> response = controller.hybridSearch("keyword", 5, null);

        assertEquals(200, response.get("code"));
        assertEquals("success", response.get("message"));
        verify(hybridSearchService).search("keyword", 5);
        verify(hybridSearchService, never()).searchWithPermission(any(), any(), anyInt());
    }

    @Test
    void hybridSearchReturnsMultipleResults() {
        List<SearchResult> results = List.of(
                new SearchResult("md5-1", 0, "text A", 0.9),
                new SearchResult("md5-2", 0, "text B", 0.8)
        );
        when(hybridSearchService.searchWithPermission("q", "uid", 10)).thenReturn(results);

        Map<String, Object> response = controller.hybridSearch("q", 10, "uid");

        assertEquals(200, response.get("code"));
        @SuppressWarnings("unchecked")
        List<?> data = (List<?>) response.get("data");
        assertEquals(2, data.size());
    }

    @Test
    void hybridSearchReturnsErrorBodyOnServiceException() {
        when(hybridSearchService.search("q", 10)).thenThrow(new RuntimeException("ES down"));

        Map<String, Object> response = controller.hybridSearch("q", 10, null);

        assertEquals(500, response.get("code"));
        assertNotNull(response.get("data")); // empty list on error
    }

    @Test
    void hybridSearchReturnsErrorBodyOnPermissionSearchException() {
        when(hybridSearchService.searchWithPermission("q", "uid", 10))
                .thenThrow(new RuntimeException("timeout"));

        Map<String, Object> response = controller.hybridSearch("q", 10, "uid");

        assertEquals(500, response.get("code"));
    }
}
