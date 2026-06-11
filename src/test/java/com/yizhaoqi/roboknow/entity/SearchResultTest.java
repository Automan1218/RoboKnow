package com.yizhaoqi.roboknow.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchResultTest {

    @Test
    void constructorWithFourArgsSetsBasicFields() {
        SearchResult r = new SearchResult("md5-abc", 3, "chunk text", 0.85);

        assertEquals("md5-abc", r.getFileMd5());
        assertEquals(3, r.getChunkId());
        assertEquals("chunk text", r.getTextContent());
        assertEquals(0.85, r.getScore());
        // unset/defaulted fields
        assertNull(r.getUserId());
        assertNull(r.getOrgTag());
        assertFalse(r.getIsPublic()); // delegates to 8-arg ctor with isPublic=false
        assertNull(r.getFileName());
    }

    @Test
    void constructorWithFiveArgsSetsFileName() {
        SearchResult r = new SearchResult("md5", 0, "text", 0.9, "report.pdf");

        assertEquals("report.pdf", r.getFileName());
        assertEquals("md5", r.getFileMd5());
        assertNull(r.getUserId());
    }

    @Test
    void constructorWithSevenArgsSetsPermissionFields() {
        SearchResult r = new SearchResult("md5", 1, "content", 0.7, "user-1", "ORG_A", true);

        assertEquals("user-1", r.getUserId());
        assertEquals("ORG_A", r.getOrgTag());
        assertTrue(r.getIsPublic());
        assertNull(r.getFileName());
    }

    @Test
    void constructorWithEightArgsSetsAllFields() {
        SearchResult r = new SearchResult("md5", 2, "full text", 0.6, "user-2", "ORG_B", false, "doc.docx");

        assertEquals("md5", r.getFileMd5());
        assertEquals(2, r.getChunkId());
        assertEquals("full text", r.getTextContent());
        assertEquals(0.6, r.getScore());
        assertEquals("user-2", r.getUserId());
        assertEquals("ORG_B", r.getOrgTag());
        assertFalse(r.getIsPublic());
        assertEquals("doc.docx", r.getFileName());
    }

    @Test
    void settersUpdateAllFields() {
        SearchResult r = new SearchResult("md5", 0, "text", 0.5);

        r.setFileMd5("new-md5");
        r.setChunkId(5);
        r.setTextContent("updated");
        r.setScore(0.99);
        r.setUserId("user-x");
        r.setOrgTag("ORG_X");
        r.setIsPublic(true);
        r.setFileName("file.pdf");
        r.setParentChunkId(1);
        r.setParentContent("parent text");

        assertEquals("new-md5", r.getFileMd5());
        assertEquals(5, r.getChunkId());
        assertEquals("updated", r.getTextContent());
        assertEquals(0.99, r.getScore());
        assertEquals("user-x", r.getUserId());
        assertEquals("ORG_X", r.getOrgTag());
        assertTrue(r.getIsPublic());
        assertEquals("file.pdf", r.getFileName());
        assertEquals(1, r.getParentChunkId());
        assertEquals("parent text", r.getParentContent());
    }

    @Test
    void equalsAndHashCodeVieLombok() {
        SearchResult r1 = new SearchResult("md5", 1, "text", 0.8, "u1", "ORG", true, "f.pdf");
        SearchResult r2 = new SearchResult("md5", 1, "text", 0.8, "u1", "ORG", true, "f.pdf");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void toStringContainsKeyFields() {
        SearchResult r = new SearchResult("my-md5", 0, "hello", 0.5);
        String s = r.toString();
        assertTrue(s.contains("my-md5") || s.contains("fileMd5"));
    }
}
