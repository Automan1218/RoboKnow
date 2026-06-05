package com.yizhaoqi.roboknow.service;

import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentServiceTest {

    private DocumentService documentService;
    private MinioClient minioClient;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService();
        minioClient = mock(MinioClient.class);
        ReflectionTestUtils.setField(documentService, "minioClient", minioClient);
    }

    @Test
    void textPreviewReadsUtf8TxtFilesDirectly() throws Exception {
        byte[] bytes = "hello\npreview text".getBytes(StandardCharsets.UTF_8);
        when(minioClient.getObject(any()))
                .thenReturn(new GetObjectResponse(Headers.of(), "uploads", null, "merged/readme.txt",
                        new ByteArrayInputStream(bytes)));

        String content = documentService.getFilePreviewContent("md5", "readme.txt");

        assertEquals("hello\npreview text\n", content);
    }

    @Test
    void binaryDocumentsAreNotClassifiedAsPlainTextFiles() throws Exception {
        Method method = DocumentService.class.getDeclaredMethod("isTextFile", String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(documentService, "txt"));
        assertFalse((Boolean) method.invoke(documentService, "doc"));
        assertFalse((Boolean) method.invoke(documentService, "docx"));
        assertFalse((Boolean) method.invoke(documentService, "pdf"));
    }

    @Test
    void docxPreviewExtractsDocumentText() throws Exception {
        byte[] bytes = minimalDocx("Quarterly preview text");
        when(minioClient.getObject(any()))
                .thenReturn(new GetObjectResponse(Headers.of(), "uploads", null, "merged/report.docx",
                        new ByteArrayInputStream(bytes)));

        String content = documentService.getFilePreviewContent("md5", "report.docx");

        assertTrue(content.contains("Quarterly preview text"));
        assertFalse(content.contains("[Content_Types]"));
    }

    private static byte[] minimalDocx(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addZipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            addZipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                        Target="word/document.xml"/>
                    </Relationships>
                    """);
            addZipEntry(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                    """.formatted(text));
        }
        return output.toByteArray();
    }

    private static void addZipEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
