package com.yizhaoqi.roboknow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileTypeValidationCoverageTest {

    private FileTypeValidationService service;

    @BeforeEach
    void setUp() {
        service = new FileTypeValidationService();
    }

    // ── supported extensions ─────────────────────────────────────────────────

    @Test
    void validateFileTypeAcceptsPdf() {
        var result = service.validateFileType("report.pdf");
        assertTrue(result.isValid());
        assertEquals("pdf", result.getExtension());
        assertTrue(result.getFileType().contains("PDF"));
    }

    @Test
    void validateFileTypeAcceptsDocxCaseInsensitively() {
        var result = service.validateFileType("DOCUMENT.DOCX");
        assertTrue(result.isValid());
        assertEquals("docx", result.getExtension());
    }

    @Test
    void validateFileTypeAcceptsExcel() {
        assertTrue(service.validateFileType("data.xls").isValid());
        assertTrue(service.validateFileType("data.xlsx").isValid());
    }

    @Test
    void validateFileTypeAcceptsPowerPoint() {
        assertTrue(service.validateFileType("slides.ppt").isValid());
        assertTrue(service.validateFileType("slides.pptx").isValid());
    }

    @Test
    void validateFileTypeAcceptsTextAndMarkdown() {
        assertTrue(service.validateFileType("readme.txt").isValid());
        assertTrue(service.validateFileType("notes.md").isValid());
        assertTrue(service.validateFileType("doc.rtf").isValid());
    }

    @Test
    void validateFileTypeAcceptsOpenDocumentFormats() {
        assertTrue(service.validateFileType("file.odt").isValid());
        assertTrue(service.validateFileType("file.ods").isValid());
        assertTrue(service.validateFileType("file.odp").isValid());
    }

    @Test
    void validateFileTypeAcceptsWebAndDataFormats() {
        assertTrue(service.validateFileType("page.html").isValid());
        assertTrue(service.validateFileType("page.htm").isValid());
        assertTrue(service.validateFileType("config.xml").isValid());
        assertTrue(service.validateFileType("data.json").isValid());
        assertTrue(service.validateFileType("table.csv").isValid());
    }

    @Test
    void validateFileTypeAcceptsEpubAndAppleFormats() {
        assertTrue(service.validateFileType("book.epub").isValid());
        assertTrue(service.validateFileType("doc.pages").isValid());
        assertTrue(service.validateFileType("sheet.numbers").isValid());
        assertTrue(service.validateFileType("pres.keynote").isValid());
    }

    // ── unsupported extensions ───────────────────────────────────────────────

    @Test
    void validateFileTypeRejectsImages() {
        assertFalse(service.validateFileType("photo.jpg").isValid());
        assertFalse(service.validateFileType("photo.jpeg").isValid());
        assertFalse(service.validateFileType("image.png").isValid());
        assertFalse(service.validateFileType("anim.gif").isValid());
        assertFalse(service.validateFileType("icon.bmp").isValid());
        assertFalse(service.validateFileType("logo.svg").isValid());
        assertFalse(service.validateFileType("photo.webp").isValid());
    }

    @Test
    void validateFileTypeRejectsAudioAndVideo() {
        assertFalse(service.validateFileType("song.mp3").isValid());
        assertFalse(service.validateFileType("video.mp4").isValid());
        assertFalse(service.validateFileType("clip.avi").isValid());
        assertFalse(service.validateFileType("clip.mov").isValid());
    }

    @Test
    void validateFileTypeRejectsArchives() {
        assertFalse(service.validateFileType("bundle.zip").isValid());
        assertFalse(service.validateFileType("bundle.rar").isValid());
        assertFalse(service.validateFileType("bundle.7z").isValid());
    }

    @Test
    void validateFileTypeRejectsExecutables() {
        assertFalse(service.validateFileType("installer.exe").isValid());
        assertFalse(service.validateFileType("installer.msi").isValid());
    }

    @Test
    void validateFileTypeRejectsBinaryFiles() {
        assertFalse(service.validateFileType("data.db").isValid());
        assertFalse(service.validateFileType("disk.iso").isValid());
        assertFalse(service.validateFileType("data.bin").isValid());
    }

    // ── unknown extension ────────────────────────────────────────────────────

    @Test
    void validateFileTypeRejectsUnknownExtensionWithHelpfulMessage() {
        var result = service.validateFileType("data.custom");
        assertFalse(result.isValid());
        assertEquals("custom", result.getExtension());
        assertTrue(result.getFileType().contains("CUSTOM"));
        assertTrue(result.getMessage().contains("CUSTOM"));
    }

    // ── null / empty / missing extension ────────────────────────────────────

    @Test
    void validateFileTypeRejectsNullFileName() {
        var result = service.validateFileType(null);
        assertFalse(result.isValid());
        assertEquals("unknown", result.getFileType());
        assertNull(result.getExtension());
    }

    @Test
    void validateFileTypeRejectsBlankFileName() {
        var result = service.validateFileType("   ");
        assertFalse(result.isValid());
        assertNull(result.getExtension());
    }

    @Test
    void validateFileTypeRejectsFileWithNoExtension() {
        var result = service.validateFileType("Makefile");
        assertFalse(result.isValid());
        assertNull(result.getExtension());
    }

    @Test
    void validateFileTypeRejectsFileWithTrailingDot() {
        var result = service.validateFileType("file.");
        assertFalse(result.isValid());
        assertNull(result.getExtension());
    }

    // ── getSupportedExtensions / getSupportedFileTypes ───────────────────────

    @Test
    void getSupportedExtensionsContainsExpectedEntries() {
        Set<String> exts = service.getSupportedExtensions();
        assertTrue(exts.contains("pdf"));
        assertTrue(exts.contains("docx"));
        assertTrue(exts.contains("xlsx"));
        assertTrue(exts.contains("txt"));
        assertTrue(exts.contains("md"));
    }

    @Test
    void getSupportedExtensionsReturnsDefensiveCopy() {
        Set<String> first = service.getSupportedExtensions();
        first.clear();
        Set<String> second = service.getSupportedExtensions();
        assertTrue(second.contains("pdf"));
    }

    @Test
    void getSupportedFileTypesIncludesKnownDescriptions() {
        Set<String> types = service.getSupportedFileTypes();
        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(t -> t.contains("PDF")));
        assertTrue(types.stream().anyMatch(t -> t.contains("Word")));
        assertTrue(types.stream().anyMatch(t -> t.contains("Excel")));
    }

    // ── FileTypeValidationResult ─────────────────────────────────────────────

    @Test
    void validationResultExposesAllFieldsAndToString() {
        var r = new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF", "pdf");
        assertTrue(r.isValid());
        assertEquals("ok", r.getMessage());
        assertEquals("PDF", r.getFileType());
        assertEquals("pdf", r.getExtension());
        String s = r.toString();
        assertTrue(s.contains("valid=true"));
        assertTrue(s.contains("extension='pdf'"));
        assertTrue(s.contains("PDF"));
    }

    @Test
    void validationResultInvalidCase() {
        var r = new FileTypeValidationService.FileTypeValidationResult(false, "not ok", "JPEG", "jpg");
        assertFalse(r.isValid());
        assertEquals("JPEG", r.getFileType());
        assertTrue(r.toString().contains("valid=false"));
    }
}
