package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.config.KafkaConfig;
import com.yizhaoqi.roboknow.entity.SearchResult;
import com.yizhaoqi.roboknow.model.FileUpload;
import com.yizhaoqi.roboknow.model.OrganizationTag;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.FileUploadRepository;
import com.yizhaoqi.roboknow.repository.OrganizationTagRepository;
import com.yizhaoqi.roboknow.repository.UserRepository;
import com.yizhaoqi.roboknow.service.ChatHandler;
import com.yizhaoqi.roboknow.service.DocumentService;
import com.yizhaoqi.roboknow.service.SessionManager;
import com.yizhaoqi.roboknow.service.FileTypeValidationService;
import com.yizhaoqi.roboknow.service.HybridSearchService;
import com.yizhaoqi.roboknow.service.UploadService;
import com.yizhaoqi.roboknow.service.UserService;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FirstPhaseControllerTest {

    private JwtUtils jwtUtils;
    private UserService userService;
    private UserRepository userRepository;
    private HybridSearchService hybridSearchService;
    private UploadService uploadService;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaConfig kafkaConfig;
    private FileUploadRepository fileUploadRepository;
    private FileTypeValidationService fileTypeValidationService;
    private DocumentService documentService;
    private OrganizationTagRepository organizationTagRepository;
    private ChatHandler chatHandler;
    private SessionManager sessionManager;
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private com.yizhaoqi.roboknow.memory.ConversationMemory conversationMemory;

    private AuthController authController;
    private UserController userController;
    private SearchController searchController;
    private UploadController uploadController;
    private DocumentController documentController;
    private ChatController chatController;
    private ConversationController conversationController;

    @BeforeEach
    void setUp() {
        jwtUtils = mock(JwtUtils.class);
        userService = mock(UserService.class);
        userRepository = mock(UserRepository.class);
        hybridSearchService = mock(HybridSearchService.class);
        uploadService = mock(UploadService.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        kafkaConfig = mock(KafkaConfig.class);
        fileUploadRepository = mock(FileUploadRepository.class);
        fileTypeValidationService = mock(FileTypeValidationService.class);
        documentService = mock(DocumentService.class);
        organizationTagRepository = mock(OrganizationTagRepository.class);
        chatHandler = mock(ChatHandler.class);
        sessionManager = mock(SessionManager.class);
        when(sessionManager.getActiveConvId("session-1")).thenReturn("test-conv-id");
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        authController = new AuthController();
        ReflectionTestUtils.setField(authController, "jwtUtils", jwtUtils);

        userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", userService);
        ReflectionTestUtils.setField(userController, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(userController, "userRepository", userRepository);

        searchController = new SearchController();
        ReflectionTestUtils.setField(searchController, "hybridSearchService", hybridSearchService);

        uploadController = new UploadController(uploadService, kafkaTemplate);
        ReflectionTestUtils.setField(uploadController, "kafkaConfig", kafkaConfig);
        ReflectionTestUtils.setField(uploadController, "userService", userService);
        ReflectionTestUtils.setField(uploadController, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadController, "fileTypeValidationService", fileTypeValidationService);

        documentController = new DocumentController();
        ReflectionTestUtils.setField(documentController, "documentService", documentService);
        ReflectionTestUtils.setField(documentController, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(documentController, "organizationTagRepository", organizationTagRepository);
        ReflectionTestUtils.setField(documentController, "jwtUtils", jwtUtils);

        chatController = new ChatController();

        conversationMemory = mock(com.yizhaoqi.roboknow.memory.ConversationMemory.class);

        conversationController = new ConversationController();
        ReflectionTestUtils.setField(conversationController, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(conversationController, "userRepository", userRepository);
        ReflectionTestUtils.setField(conversationController, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(conversationController, "conversationMemory", conversationMemory);
    }

    @Test
    void authRefreshTokenCoversValidationSuccessAndErrorPaths() {
        assertEquals(HttpStatus.BAD_REQUEST,
                authController.refreshToken(new RefreshTokenRequest("")).getStatusCode());

        when(jwtUtils.validateRefreshToken("bad")).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED,
                authController.refreshToken(new RefreshTokenRequest("bad")).getStatusCode());

        when(jwtUtils.validateRefreshToken("valid")).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken("valid")).thenReturn("alice");
        when(jwtUtils.generateToken("alice")).thenReturn("access");
        when(jwtUtils.generateRefreshToken("alice")).thenReturn("refresh");

        ResponseEntity<?> response = authController.refreshToken(new RefreshTokenRequest("valid"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(200, body(response).get("code"));
        assertEquals(HttpStatus.I_AM_A_TEAPOT, authController.customBackendError("418", "x").getStatusCode());
    }

    @Test
    void userRegistrationLoginProfileAndLogoutCoverFirstPhaseUserManagement() {
        assertEquals(HttpStatus.BAD_REQUEST, userController.register(new UserRequest("", "p")).getStatusCode());

        ResponseEntity<?> register = userController.register(new UserRequest("alice", "secret"));
        assertEquals(HttpStatus.OK, register.getStatusCode());
        verify(userService).registerUser("alice", "secret");

        assertEquals(HttpStatus.BAD_REQUEST, userController.login(new UserRequest("alice", "")).getStatusCode());
        when(userService.authenticateUser("alice", "secret")).thenReturn("alice");
        when(jwtUtils.generateToken("alice")).thenReturn("access");
        when(jwtUtils.generateRefreshToken("alice")).thenReturn("refresh");
        assertEquals(HttpStatus.OK, userController.login(new UserRequest("alice", "secret")).getStatusCode());

        when(userService.authenticateUser("alice", "wrong")).thenReturn(null);
        assertEquals(HttpStatus.UNAUTHORIZED, userController.login(new UserRequest("alice", "wrong")).getStatusCode());

        User user = user("alice", "ORG_A,ORG_B");
        when(jwtUtils.extractUsernameFromToken("token")).thenReturn("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        assertEquals(HttpStatus.OK, userController.getCurrentUser("Bearer token").getStatusCode());

        when(userService.getUserOrgTags("alice")).thenReturn(Map.of("orgTags", "ORG_A,ORG_B"));
        when(userService.getUserPrimaryOrg("alice")).thenReturn("ORG_A");
        assertEquals(HttpStatus.OK, userController.getUserOrgTags("Bearer token").getStatusCode());
        assertEquals(HttpStatus.OK, userController.getUploadOrgTags("alice").getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
                userController.setPrimaryOrg("Bearer token", new PrimaryOrgRequest("")).getStatusCode());
        assertEquals(HttpStatus.OK,
                userController.setPrimaryOrg("Bearer token", new PrimaryOrgRequest("ORG_A")).getStatusCode());
        verify(userService).setUserPrimaryOrg("alice", "ORG_A");

        assertEquals(HttpStatus.BAD_REQUEST, userController.logout("bad").getStatusCode());
        assertEquals(HttpStatus.OK, userController.logout("Bearer token").getStatusCode());
        verify(jwtUtils).invalidateToken("token");

        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");
        assertEquals(HttpStatus.OK, userController.logoutAll("Bearer token").getStatusCode());
        verify(jwtUtils).invalidateAllUserTokens("1");
    }

    @Test
    void searchControllerCoversAnonymousPermissionAndFailureBranches() {
        List<SearchResult> publicResults = List.of(new SearchResult("md5", 1, "text", 0.9));
        List<SearchResult> privateResults = List.of(new SearchResult("md6", 2, "secret", 0.8, "alice", "ORG", false));
        when(hybridSearchService.search("java", 3)).thenReturn(publicResults);
        when(hybridSearchService.searchWithPermission("java", "alice", 3)).thenReturn(privateResults);

        Map<String, Object> anonymous = searchController.hybridSearch("java", 3, null);
        assertEquals(200, anonymous.get("code"));
        assertSame(publicResults, anonymous.get("data"));

        Map<String, Object> permission = searchController.hybridSearch("java", 3, "alice");
        assertEquals(200, permission.get("code"));
        assertSame(privateResults, permission.get("data"));

        when(hybridSearchService.search("boom", 10)).thenThrow(new RuntimeException("search failed"));
        Map<String, Object> error = searchController.hybridSearch("boom", 10, null);
        assertEquals(500, error.get("code"));
        assertEquals(List.of(), error.get("data"));
    }

    @Test
    void uploadControllerCoversChunkStatusMergeAndSupportedTypes() throws Exception {
        when(fileTypeValidationService.getSupportedFileTypes()).thenReturn(Set.of("PDF"));
        when(fileTypeValidationService.getSupportedExtensions()).thenReturn(Set.of("pdf"));
        assertEquals(HttpStatus.OK, uploadController.getSupportedFileTypes().getStatusCode());

        when(fileTypeValidationService.validateFileType("bad.exe"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(false, "unsupported", "EXE", "exe"));
        ResponseEntity<Map<String, Object>> invalid = uploadController.uploadChunk(
                "md5", 0, 10, "bad.exe", 1, "ORG", false, multipart(), "alice");
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());

        when(fileTypeValidationService.validateFileType("doc.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF", "pdf"));
        when(uploadService.getUploadedChunks("md5", "alice")).thenReturn(List.of(0));
        when(uploadService.getTotalChunks("md5", "alice")).thenReturn(2);
        ResponseEntity<Map<String, Object>> uploaded = uploadController.uploadChunk(
                "md5", 0, 10, "doc.pdf", 2, "ORG", false, multipart(), "alice");
        assertEquals(HttpStatus.OK, uploaded.getStatusCode());

        FileUpload file = file("md5", "doc.pdf", "alice", "ORG", false);
        when(fileUploadRepository.findByFileMd5("md5")).thenReturn(Optional.of(file));
        assertEquals(HttpStatus.OK, uploadController.getUploadStatus("md5", "alice").getStatusCode());

        when(fileUploadRepository.findByFileMd5AndUserId("md5", "alice")).thenReturn(Optional.of(file));
        when(uploadService.getUploadedChunks("md5", "alice")).thenReturn(List.of(0, 1));
        when(uploadService.getTotalChunks("md5", "alice")).thenReturn(2);
        when(uploadService.mergeChunks("md5", "doc.pdf", "alice")).thenReturn("http://minio/doc.pdf");
        when(kafkaConfig.getFileProcessingTopic()).thenReturn("files");
        when(kafkaTemplate.executeInTransaction(any())).thenReturn(true);
        assertEquals(HttpStatus.OK,
                uploadController.mergeFile(new UploadController.MergeRequest("md5", "doc.pdf"), "alice").getStatusCode());
    }

    @Test
    void documentControllerCoversDeleteListDownloadAndPreview() {
        assertEquals(HttpStatus.NOT_FOUND, documentController.deleteDocument("missing", "alice", "USER").getStatusCode());

        FileUpload file = file("md5", "doc.txt", "alice", "ORG", true);
        when(fileUploadRepository.findByFileMd5AndUserId("md5", "alice")).thenReturn(Optional.of(file));
        assertEquals(HttpStatus.OK, documentController.deleteDocument("md5", "alice", "USER").getStatusCode());
        verify(documentService).deleteDocument("md5", "alice");

        when(documentService.getAccessibleFiles("alice", "ORG")).thenReturn(List.of(file));
        assertEquals(HttpStatus.OK, documentController.getAccessibleFiles("alice", "ORG").getStatusCode());

        OrganizationTag tag = new OrganizationTag();
        tag.setTagId("ORG");
        tag.setName("Engineering");
        when(documentService.getUserUploadedFiles("alice")).thenReturn(List.of(file));
        when(organizationTagRepository.findByTagId("ORG")).thenReturn(Optional.of(tag));
        assertEquals(HttpStatus.OK, documentController.getUserUploadedFiles("alice").getStatusCode());

        when(fileUploadRepository.findByFileNameAndIsPublicTrue("doc.txt")).thenReturn(Optional.of(file));
        when(documentService.generateDownloadUrl("md5")).thenReturn("http://download");
        assertEquals(HttpStatus.OK, documentController.downloadFileByName("doc.txt", null, null).getStatusCode());

        when(jwtUtils.extractUsernameFromToken("token")).thenReturn("alice");
        when(jwtUtils.extractOrgTagsFromToken("token")).thenReturn("ORG");
        assertEquals(HttpStatus.OK, documentController.downloadFileByName("doc.txt", "token", null).getStatusCode());

        when(documentService.getFilePreviewContent("md5", "doc.txt")).thenReturn("hello");
        assertEquals(HttpStatus.OK, documentController.previewFileByName("doc.txt", null, null).getStatusCode());
        assertEquals(HttpStatus.OK, documentController.previewFileByName("doc.txt", "token", null).getStatusCode());
    }

    @Test
    void chatControllerCoversHttpToken() {
        // ChatController 的 handleTextMessage/WebSocket 分发路径 2026-07-16 已删除：
        // 那个 TextWebSocketHandler 覆写从未被注册为实际 WebSocket handler（真正接
        // /chat/{token} 的是 WebSocketConfig 里的 chatWebSocketHandler），是死代码。
        // 现在的 ChatController 只剩 /websocket-token 这一个真实端点。
        ResponseEntity<?> token = chatController.getWebSocketToken();
        assertEquals(HttpStatus.OK, token.getStatusCode());
    }

    @Test
    void conversationControllerCoversEmptyAndFilteredHistory() {
        User user = user("alice", "ORG_A");
        when(jwtUtils.extractUsernameFromToken("token")).thenReturn("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        ResponseEntity<?> empty = conversationController.getConversations("Bearer token", null, null);

        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertEquals(200, body(empty).get("code"));

        when(valueOperations.get("user:1:current_conversation")).thenReturn("conv-1");
        when(conversationMemory.loadHistory("conv-1")).thenReturn(List.of(
                Map.of("role", "user", "content", "early", "timestamp", "2025-01-01T09:00:00"),
                Map.of("role", "assistant", "content", "inside", "timestamp", "2025-01-02T09:00:00"),
                Map.of("role", "user", "content", "late", "timestamp", "2025-01-03T09:00:00"),
                Map.of("role", "assistant", "content", "unknown")));

        ResponseEntity<?> filtered = conversationController.getConversations(
                "Bearer token", "2025-01-02", "2025-01-02T23:59");

        assertEquals(HttpStatus.OK, filtered.getStatusCode());
        Map<String, Object> response = body(filtered);
        assertEquals(200, response.get("code"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertEquals(1, data.size());
        assertEquals("inside", data.get(0).get("content"));

        ResponseEntity<?> invalidDate = conversationController.getConversations(
                "Bearer token", "not-a-date", null);
        assertEquals(HttpStatus.BAD_REQUEST, invalidDate.getStatusCode());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    private static MockMultipartFile multipart() {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf", "hello".getBytes());
    }

    private static User user(String username, String orgTags) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        user.setPassword("secret");
        user.setRole(User.Role.USER);
        user.setOrgTags(orgTags);
        user.setPrimaryOrg("ORG_A");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private static FileUpload file(String md5, String name, String userId, String orgTag, boolean isPublic) {
        FileUpload file = new FileUpload();
        file.setId(10L);
        file.setFileMd5(md5);
        file.setFileName(name);
        file.setTotalSize(2048L);
        file.setStatus(1);
        file.setUserId(userId);
        file.setOrgTag(orgTag);
        file.setPublic(isPublic);
        file.setCreatedAt(LocalDateTime.now());
        file.setMergedAt(LocalDateTime.now());
        return file;
    }
}
