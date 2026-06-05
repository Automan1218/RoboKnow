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

/**
 * 文档管理服务类
 * 负责文档的删除等管理操作
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
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userIdOrUsername));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userIdOrUsername)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userIdOrUsername));
        }
    }

    /**
     * 删除文档及其相关数据
     * 该方法将删除:
     * 1. FileUpload记录
     * 2. DocumentVector记录
     * 3. MinIO中的文件
     * 4. Elasticsearch中的向量数据
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);
        
        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));
            
            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("成功从Elasticsearch删除文档: {}", fileMd5);
            } catch (Exception e) {
                logger.error("从Elasticsearch删除文档时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 2. 删除MinIO中的文件
            try {
                String objectName = "merged/" + fileUpload.getFileName();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("成功从MinIO删除文件: {}", objectName);
            } catch (Exception e) {
                logger.error("从MinIO删除文件时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档向量记录: {}", fileMd5);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("成功删除文件上传记录: {}", fileMd5);
            
            logger.info("文档删除完成: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}", fileMd5, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户可访问的所有文件列表
     * 包括用户自己的文件、公开文件和用户所属组织的文件（支持层级权限）
     *
     * @param userId 用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("获取用户可访问文件列表: userId={}", userId);
        
        try {
            // 获取用户有效的组织标签（包含层级关系）
            User user = resolveUser(userId);
            String dbUserId = user.getId().toString();
            List<String> ownerIds = List.of(dbUserId, user.getUsername());
            
            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户有效组织标签: {}", userEffectiveTags);
            
            // 使用有效标签查询文件
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                files = fileUploadRepository.findByUserIdInOrIsPublicTrue(ownerIds);
                logger.debug("用户无组织标签，仅返回个人和公开文件");
            } else {
                files = fileUploadRepository.findAccessibleFilesWithTags(ownerIds, userEffectiveTags);
                logger.debug("使用有效组织标签查询文件");
            }
            
            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户上传的所有文件列表
     *
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);
        
        try {
            User user = resolveUser(userId);
            List<FileUpload> files = fileUploadRepository.findByUserIdIn(List.of(user.getId().toString(), user.getUsername()));
            logger.info("成功获取用户上传的文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成文件下载链接
     * 
     * @param fileMd5 文件MD5
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("生成文件下载链接: fileMd5={}", fileMd5);
        
        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));
            
            // MinIO中的对象路径格式: merged/文件名
            String objectName = "merged/" + fileUpload.getFileName();
            
            // 生成预签名URL，有效期1小时
            // response-content-disposition=inline 让浏览器内联展示而非下载
            Map<String, String> extraQueryParams = new HashMap<>();
            extraQueryParams.put("response-content-disposition", "inline");
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket("uploads")
                            .object(objectName)
                            .expiry(3600) // 1小时有效期
                            .extraQueryParams(extraQueryParams)
                            .build()
            );
            
            logger.info("成功生成文件下载链接: fileMd5={}, fileName={}, objectName={}", 
                    fileMd5, fileUpload.getFileName(), objectName);
            return presignedUrl;
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }
    
    /**
     * 获取文件预览内容
     * 
     * @param fileMd5 文件MD5
     * @param fileName 文件名
     * @return 文件预览内容，对于文本文件返回前几KB内容，非文本文件返回文件信息
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);
        
        try {
            // MinIO中的对象路径格式: merged/文件名
            String objectName = "merged/" + fileName;
            
            // 判断文件类型
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isTextFile = isTextFile(fileExtension);
            
            if (isTextFile) {
                // 对于文本文件，读取前10KB内容
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
                    
                    logger.info("成功获取文本文件预览内容: fileMd5={}, contentLength={}", fileMd5, result.length());
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
                // 对于非文本文件，返回文件信息
                FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                        .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));
                
                String fileInfo = String.format(
                    "文件名: %s\n" +
                    "文件大小: %s\n" +
                    "文件类型: %s\n" +
                    "上传时间: %s\n\n" +
                    "此文件类型不支持预览，请下载后查看。",
                    fileName,
                    formatFileSize(fileUpload.getTotalSize()),
                    fileExtension.toUpperCase(),
                    fileUpload.getCreatedAt()
                );
                
                logger.info("返回非文本文件信息: fileMd5={}", fileMd5);
                return fileInfo;
            }
            
        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "预览失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }
    
    /**
     * 判断是否为文本文件
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
     * 从 MinIO 流式读取文件，供 Controller 直接响应给浏览器（inline 展示）
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
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null) return "未知";
        
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
