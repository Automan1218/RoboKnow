package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 集中化权限服务，提供 RBAC（角色）和 ABAC（组织标签）双维度权限解析。
 *
 * RBAC：基于 User.role 判断管理员/普通用户操作权限。
 * ABAC：基于组织标签层级（orgTag 继承链）确定文档可见范围。
 */
@Service
public class PermissionService {

    private static final Logger logger = LoggerFactory.getLogger(PermissionService.class);

    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;

    public PermissionService(UserRepository userRepository, OrgTagCacheService orgTagCacheService) {
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
    }

    /**
     * 将 userId（可能是 username 或数字 ID 字符串）解析为数据库中的数字 ID 字符串。
     */
    public String resolveDbId(String userId) {
        try {
            try {
                Long id = Long.parseLong(userId);
                userRepository.findById(id)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return id.toString();
            } catch (NumberFormatException e) {
                User user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return user.getId().toString();
            }
        } catch (Exception e) {
            logger.error("resolveDbId 失败，userId={}: {}", userId, e.getMessage());
            throw new RuntimeException("无法解析用户ID: " + userId, e);
        }
    }

    /**
     * 获取用户有效的组织标签列表（含父级标签继承链）。
     */
    public List<String> getUserEffectiveOrgTags(String userId) {
        try {
            String username = resolveUsername(userId);
            List<String> tags = orgTagCacheService.getUserEffectiveOrgTags(username);
            logger.debug("用户 {} 有效组织标签: {}", username, tags);
            return tags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败，userId={}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * RBAC 检查：用户是否拥有管理员角色。
     */
    public boolean isAdmin(String userId) {
        try {
            User user = loadUser(userId);
            return User.Role.ADMIN.equals(user.getRole());
        } catch (Exception e) {
            logger.warn("isAdmin 检查失败，userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    private String resolveUsername(String userId) {
        try {
            Long id = Long.parseLong(userId);
            return userRepository.findById(id)
                .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND))
                .getUsername();
        } catch (NumberFormatException e) {
            return userId;
        }
    }

    private User loadUser(String userId) {
        try {
            Long id = Long.parseLong(userId);
            return userRepository.findById(id)
                .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }
}
