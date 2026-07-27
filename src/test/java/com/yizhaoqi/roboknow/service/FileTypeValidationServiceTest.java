package com.yizhaoqi.roboknow.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileTypeValidationServiceTest {

    private FileTypeValidationService service;

    @BeforeEach
    void setUp() {
        service = new FileTypeValidationService();
    }

    @Test
    void validateFileTypeAcceptsSupportedExtensionsCaseInsensitively() {
        FileTypeValidationService.FileTypeValidationResult result = service.validateFileType("report.PDF");

        assertTrue(result.isValid());
        assertEquals("pdf", result.getExtension());
        assertTrue(result.getFileType().contains("PDF"));
    }

    @Test
    void validateFileTypeRejectsBlankNamesAndMissingExtensions() {
        assertInvalidWithoutExtension(service.validateFileType(null));
        assertInvalidWithoutExtension(service.validateFileType("   "));
        assertInvalidWithoutExtension(service.validateFileType("filename"));
        assertInvalidWithoutExtension(service.validateFileType("filename."));
    }

    @Test
    void validateFileTypeRejectsUnsupportedAndUnknownExtensions() {
        FileTypeValidationService.FileTypeValidationResult image = service.validateFileType("avatar.png");
        assertFalse(image.isValid());
        assertEquals("png", image.getExtension());
        assertTrue(image.getFileType().contains("PNG"));

        FileTypeValidationService.FileTypeValidationResult unknown = service.validateFileType("data.custom");
        assertFalse(unknown.isValid());
        assertEquals("custom", unknown.getExtension());
        assertTrue(unknown.getFileType().contains("CUSTOM"));
    }

    @Test
    void getSupportedExtensionsReturnsDefensiveCopy() {
        Set<String> first = service.getSupportedExtensions();

        assertTrue(first.contains("pdf"));
        assertTrue(first.contains("docx"));

        first.clear();

        Set<String> second = service.getSupportedExtensions();
        assertTrue(second.contains("pdf"));
        assertTrue(second.contains("docx"));
    }

    @Test
    void getSupportedFileTypesIncludesDescriptionsForKnownDocumentTypes() {
        Set<String> types = service.getSupportedFileTypes();

        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(type -> type.contains("PDF")));
        assertTrue(types.stream().anyMatch(type -> type.contains("Word")));
        assertTrue(types.stream().anyMatch(type -> type.contains("Excel")));
    }

    @Test
    void validationResultExposesFieldsAndReadableString() {
        FileTypeValidationService.FileTypeValidationResult result =
                new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF", "pdf");

        assertTrue(result.isValid());
        assertEquals("ok", result.getMessage());
        assertEquals("PDF", result.getFileType());
        assertEquals("pdf", result.getExtension());
        assertTrue(result.toString().contains("valid=true"));
        assertTrue(result.toString().contains("extension='pdf'"));
    }

    private static void assertInvalidWithoutExtension(FileTypeValidationService.FileTypeValidationResult result) {
        assertFalse(result.isValid());
        assertEquals("unknown", result.getFileType());
        assertNull(result.getExtension());
    }
}
