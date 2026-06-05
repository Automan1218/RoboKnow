package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.FileUpload;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.DocumentVectorRepository;
import com.yizhaoqi.roboknow.repository.FileUploadRepository;
import com.yizhaoqi.roboknow.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * æ–‡æ¡£ç®¡ç†æœåŠ¡ç±»
 * è´Ÿè´£æ–‡æ¡£çš„åˆ é™¤ç­‰ç®¡ç†æ“ä½œ
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    private static final int PREVIEW_MAX_CHARS = 10240;
    private static final String PREVIEW_TRUNCATED_MESSAGE = "\n... (content truncated, showing first 10KB)";

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    private User resolveUser(String userIdOrUsername) {
        try {
            Long id = Long.parseLong(userIdOrUsername);
            return userRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("ç”¨æˆ·ä¸å­˜åœ¨: " + userIdOrUsername));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userIdOrUsername)
                    .orElseThrow(() -> new RuntimeException("ç”¨æˆ·ä¸å­˜åœ¨: " + userIdOrUsername));
        }
    }

    /**
     * åˆ é™¤æ–‡æ¡£åŠå…¶ç›¸å…³æ•°æ®
     * è¯¥æ–¹æ³•å°†åˆ é™¤:
     * 1. FileUploadè®°å½•
     * 2. DocumentVectorè®°å½•
     * 3. MinIOä¸­çš„æ–‡ä»¶
     * 4. Elasticsearchä¸­çš„å‘é‡æ•°æ®
     *
     * @param fileMd5 æ–‡ä»¶MD5
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("å¼€å§‹åˆ é™¤æ–‡æ¡£: {}", fileMd5);
        
        try {
            // èŽ·å–æ–‡ä»¶ä¿¡æ¯ä»¥èŽ·å–æ–‡ä»¶å
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("æ–‡ä»¶ä¸å­˜åœ¨"));
            
            // 1. åˆ é™¤Elasticsearchä¸­çš„æ•°æ®
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("æˆåŠŸä»ŽElasticsearchåˆ é™¤æ–‡æ¡£: {}", fileMd5);
            } catch (Exception e) {
                logger.error("ä»ŽElasticsearchåˆ é™¤æ–‡æ¡£æ—¶å‡ºé”™: {}", fileMd5, e);
                // ç»§ç»­åˆ é™¤å…¶ä»–æ•°æ®
            }
            
            // 2. åˆ é™¤MinIOä¸­çš„æ–‡ä»¶
            try {
                String objectName = "merged/" + fileUpload.getFileName();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("æˆåŠŸä»ŽMinIOåˆ é™¤æ–‡ä»¶: {}", objectName);
            } catch (Exception e) {
                logger.error("ä»ŽMinIOåˆ é™¤æ–‡ä»¶æ—¶å‡ºé”™: {}", fileMd5, e);
                // ç»§ç»­åˆ é™¤å…¶ä»–æ•°æ®
            }
            
            // 3. åˆ é™¤DocumentVectorè®°å½•
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("æˆåŠŸåˆ é™¤æ–‡æ¡£å‘é‡è®°å½•: {}", fileMd5);
            } catch (Exception e) {
                logger.error("åˆ é™¤æ–‡æ¡£å‘é‡è®°å½•æ—¶å‡ºé”™: {}", fileMd5, e);
                // ç»§ç»­åˆ é™¤å…¶ä»–æ•°æ®
            }
            
            // 4. åˆ é™¤FileUploadè®°å½•
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("æˆåŠŸåˆ é™¤æ–‡ä»¶ä¸Šä¼ è®°å½•: {}", fileMd5);
            
            logger.info("æ–‡æ¡£åˆ é™¤å®Œæˆ: {}", fileMd5);
        } catch (Exception e) {
            logger.error("åˆ é™¤æ–‡æ¡£è¿‡ç¨‹ä¸­å‘ç”Ÿé”™è¯¯: {}", fileMd5, e);
            throw new RuntimeException("åˆ é™¤æ–‡æ¡£å¤±è´¥: " + e.getMessage(), e);
        }
    }
    
    /**
     * èŽ·å–ç”¨æˆ·å¯è®¿é—®çš„æ‰€æœ‰æ–‡ä»¶åˆ—è¡¨
     * åŒ…æ‹¬ç”¨æˆ·è‡ªå·±çš„æ–‡ä»¶ã€å…¬å¼€æ–‡ä»¶å’Œç”¨æˆ·æ‰€å±žç»„ç»‡çš„æ–‡ä»¶ï¼ˆæ”¯æŒå±‚çº§æƒé™ï¼‰
     *
     * @param userId ç”¨æˆ·ID
     * @param orgTags ç”¨æˆ·æ‰€å±žçš„ç»„ç»‡æ ‡ç­¾ï¼ˆé€—å·åˆ†éš”çš„å­—ç¬¦ä¸²ï¼Œä»…ä¾›å…¼å®¹æ€§ä½¿ç”¨ï¼‰
     * @return ç”¨æˆ·å¯è®¿é—®çš„æ–‡ä»¶åˆ—è¡¨
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("èŽ·å–ç”¨æˆ·å¯è®¿é—®æ–‡ä»¶åˆ—è¡¨: userId={}", userId);
        
        try {
            // èŽ·å–ç”¨æˆ·æœ‰æ•ˆçš„ç»„ç»‡æ ‡ç­¾ï¼ˆåŒ…å«å±‚çº§å…³ç³»ï¼‰
            User user = resolveUser(userId);
            String dbUserId = user.getId().toString();
            List<String> ownerIds = List.of(dbUserId, user.getUsername());
            
            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("ç”¨æˆ·æœ‰æ•ˆç»„ç»‡æ ‡ç­¾: {}", userEffectiveTags);
            
            // ä½¿ç”¨æœ‰æ•ˆæ ‡ç­¾æŸ¥è¯¢æ–‡ä»¶
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // å¦‚æžœç”¨æˆ·æ²¡æœ‰ä»»ä½•ç»„ç»‡æ ‡ç­¾ï¼Œåªè¿”å›žè‡ªå·±çš„æ–‡ä»¶å’Œå…¬å¼€æ–‡ä»¶
                files = fileUploadRepository.findByUserIdInOrIsPublicTrue(ownerIds);
                logger.debug("ç”¨æˆ·æ— ç»„ç»‡æ ‡ç­¾ï¼Œä»…è¿”å›žä¸ªäººå’Œå…¬å¼€æ–‡ä»¶");
            } else {
                // æŸ¥è¯¢ç”¨æˆ·å¯è®¿é—®çš„æ‰€æœ‰æ–‡ä»¶ï¼ˆè€ƒè™‘å±‚çº§æ ‡ç­¾ï¼‰
                files = fileUploadRepository.findAccessibleFilesWithTags(ownerIds);
                logger.debug("ä½¿ç”¨æœ‰æ•ˆç»„ç»‡æ ‡ç­¾æŸ¥è¯¢æ–‡ä»¶");
            }
            
            logger.info("æˆåŠŸèŽ·å–ç”¨æˆ·å¯è®¿é—®æ–‡ä»¶åˆ—è¡¨: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("èŽ·å–ç”¨æˆ·å¯è®¿é—®æ–‡ä»¶åˆ—è¡¨å¤±è´¥: userId={}", userId, e);
            throw new RuntimeException("èŽ·å–å¯è®¿é—®æ–‡ä»¶åˆ—è¡¨å¤±è´¥: " + e.getMessage(), e);
        }
    }
    
    /**
     * èŽ·å–ç”¨æˆ·ä¸Šä¼ çš„æ‰€æœ‰æ–‡ä»¶åˆ—è¡¨
     *
     * @param userId ç”¨æˆ·ID
     * @return ç”¨æˆ·ä¸Šä¼ çš„æ–‡ä»¶åˆ—è¡¨
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("èŽ·å–ç”¨æˆ·ä¸Šä¼ çš„æ–‡ä»¶åˆ—è¡¨: userId={}", userId);
        
        try {
            User user = resolveUser(userId);
            List<FileUpload> files = fileUploadRepository.findByUserIdIn(List.of(user.getId().toString(), user.getUsername()));
            logger.info("æˆåŠŸèŽ·å–ç”¨æˆ·ä¸Šä¼ çš„æ–‡ä»¶åˆ—è¡¨: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("èŽ·å–ç”¨æˆ·ä¸Šä¼ çš„æ–‡ä»¶åˆ—è¡¨å¤±è´¥: userId={}", userId, e);
            throw new RuntimeException("èŽ·å–ç”¨æˆ·ä¸Šä¼ çš„æ–‡ä»¶åˆ—è¡¨å¤±è´¥: " + e.getMessage(), e);
        }
    }
    
    /**
     * ç”Ÿæˆæ–‡ä»¶ä¸‹è½½é“¾æŽ¥
     * 
     * @param fileMd5 æ–‡ä»¶MD5
     * @return é¢„ç­¾åä¸‹è½½URL
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("ç”Ÿæˆæ–‡ä»¶ä¸‹è½½é“¾æŽ¥: fileMd5={}", fileMd5);
        
        try {
            // ä»Žæ•°æ®åº“èŽ·å–æ–‡ä»¶ä¿¡æ¯
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("æ–‡ä»¶ä¸å­˜åœ¨: " + fileMd5));
            
            // MinIOä¸­çš„å¯¹è±¡è·¯å¾„æ ¼å¼: merged/æ–‡ä»¶å
            String objectName = "merged/" + fileUpload.getFileName();
            
            // ç”Ÿæˆé¢„ç­¾åURLï¼Œæœ‰æ•ˆæœŸ1å°æ—¶
            // response-content-disposition=inline è®©æµè§ˆå™¨å†…è”å±•ç¤ºè€Œéžä¸‹è½½
            Map<String, String> extraQueryParams = new HashMap<>();
            extraQueryParams.put("response-content-disposition", "inline");
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600) // 1å°æ—¶æœ‰æ•ˆæœŸ
                            .extraQueryParams(extraQueryParams)
                            .build()
            );
            
            logger.info("æˆåŠŸç”Ÿæˆæ–‡ä»¶ä¸‹è½½é“¾æŽ¥: fileMd5={}, fileName={}, objectName={}", 
                    fileMd5, fileUpload.getFileName(), objectName);
            return presignedUrl;
        } catch (Exception e) {
            logger.error("ç”Ÿæˆæ–‡ä»¶ä¸‹è½½é“¾æŽ¥å¤±è´¥: fileMd5={}", fileMd5, e);
            return null;
        }
    }
    
    /**
     * èŽ·å–æ–‡ä»¶é¢„è§ˆå†…å®¹
     * 
     * @param fileMd5 æ–‡ä»¶MD5
     * @param fileName æ–‡ä»¶å
     * @return æ–‡ä»¶é¢„è§ˆå†…å®¹ï¼Œå¯¹äºŽæ–‡æœ¬æ–‡ä»¶è¿”å›žå‰å‡ KBå†…å®¹ï¼Œéžæ–‡æœ¬æ–‡ä»¶è¿”å›žæ–‡ä»¶ä¿¡æ¯
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("èŽ·å–æ–‡ä»¶é¢„è§ˆå†…å®¹: fileMd5={}, fileName={}", fileMd5, fileName);
        
        try {
            // MinIOä¸­çš„å¯¹è±¡è·¯å¾„æ ¼å¼: merged/æ–‡ä»¶å
            String objectName = "merged/" + fileName;
            
            // åˆ¤æ–­æ–‡ä»¶ç±»åž‹
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isTextFile = isTextFile(fileExtension);
            
            if (isTextFile) {
                // å¯¹äºŽæ–‡æœ¬æ–‡ä»¶ï¼Œè¯»å–å‰10KBå†…å®¹
                try (InputStream inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build())) {
                    
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    StringBuilder content = new StringBuilder();
                    String line;
                    int bytesRead = 0;
                    int maxBytes = PREVIEW_MAX_CHARS;
                    
                    while ((line = reader.readLine()) != null && bytesRead < maxBytes) {
                        content.append(line).append("\n");
                        bytesRead += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    }
                    
                    String result = content.toString();
                    if (bytesRead >= maxBytes) {
                        result += PREVIEW_TRUNCATED_MESSAGE;
                    }
                    
                    logger.info("æˆåŠŸèŽ·å–æ–‡æœ¬æ–‡ä»¶é¢„è§ˆå†…å®¹: fileMd5={}, contentLength={}", fileMd5, result.length());
                    return result;
                }
            } else if (isExtractableDocument(fileExtension)) {
                try (InputStream inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build())) {

                    String result = extractDocumentText(inputStream, fileName);
                    logger.info("Document preview text extracted: fileMd5={}, contentLength={}", fileMd5, result.length());
                    return result;
                }
            } else {
                // å¯¹äºŽéžæ–‡æœ¬æ–‡ä»¶ï¼Œè¿”å›žæ–‡ä»¶ä¿¡æ¯
                FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                        .orElseThrow(() -> new RuntimeException("æ–‡ä»¶ä¸å­˜åœ¨: " + fileMd5));
                
                String fileInfo = String.format(
                    "æ–‡ä»¶å: %s\n" +
                    "æ–‡ä»¶å¤§å°: %s\n" +
                    "æ–‡ä»¶ç±»åž‹: %s\n" +
                    "ä¸Šä¼ æ—¶é—´: %s\n\n" +
                    "æ­¤æ–‡ä»¶ç±»åž‹ä¸æ”¯æŒé¢„è§ˆï¼Œè¯·ä¸‹è½½åŽæŸ¥çœ‹ã€‚",
                    fileName,
                    formatFileSize(fileUpload.getTotalSize()),
                    fileExtension.toUpperCase(),
                    fileUpload.getCreatedAt()
                );
                
                logger.info("è¿”å›žéžæ–‡æœ¬æ–‡ä»¶ä¿¡æ¯: fileMd5={}", fileMd5);
                return fileInfo;
            }
            
        } catch (Exception e) {
            logger.error("èŽ·å–æ–‡ä»¶é¢„è§ˆå†…å®¹å¤±è´¥: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "é¢„è§ˆå¤±è´¥: " + e.getMessage();
        }
    }
    
    /**
     * èŽ·å–æ–‡ä»¶æ‰©å±•å
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }
    
    /**
     * åˆ¤æ–­æ˜¯å¦ä¸ºæ–‡æœ¬æ–‡ä»¶
     */
    private boolean isTextFile(String extension) {
        String[] textExtensions = {
            "txt", "md", "html", "htm", "xml", "json", 
            "csv", "log", "java", "js", "ts", "py", "cpp", "c", "h", "css", 
            "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config"
        };
        
        return Arrays.stream(textExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }

    private boolean isExtractableDocument(String extension) {
        String[] extractableExtensions = { "doc", "docx", "pdf" };

        return Arrays.stream(extractableExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }

    private String extractDocumentText(InputStream inputStream, String fileName) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        String text = new Tika().parseToString(inputStream, metadata, PREVIEW_MAX_CHARS).trim();
        if (text.isEmpty()) {
            return "No previewable text was extracted. Please download the file to view it.";
        }

        if (text.length() >= PREVIEW_MAX_CHARS) {
            return text + PREVIEW_TRUNCATED_MESSAGE;
        }

        return text;
    }
    
    /**
     * ä»Ž MinIO æµå¼è¯»å–æ–‡ä»¶ï¼Œä¾› Controller ç›´æŽ¥å“åº”ç»™æµè§ˆå™¨ï¼ˆinline å±•ç¤ºï¼‰
     */
    public InputStream streamFile(String fileName) throws Exception {
        String objectName = "merged/" + fileName;
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("uploads")
                        .object(objectName)
                        .build());
    }

    /**
     * æ ¼å¼åŒ–æ–‡ä»¶å¤§å°
     */
    private String formatFileSize(Long size) {
        if (size == null) return "æœªçŸ¥";
        
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
} 
