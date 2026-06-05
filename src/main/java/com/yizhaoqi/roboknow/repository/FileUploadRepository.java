package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findByFileMd5(String fileMd5);
    
    Optional<FileUpload> findByFileMd5AndUserId(String fileMd5, String userId);
    
    Optional<FileUpload> findByFileNameAndIsPublicTrue(String fileName);
    
    long countByFileMd5(String fileMd5);
    
    void deleteByFileMd5(String fileMd5);
    
    void deleteByFileMd5AndUserId(String fileMd5, String userId);
    
    /**
     * 查询用户自己的文件和公开文件
     */
    List<FileUpload> findByUserIdOrIsPublicTrue(String userId);

    List<FileUpload> findByUserIdInOrIsPublicTrue(List<String> userIds);
    
    /**
     * 查询用户可访问的所有文件（考虑层级标签权限）
     * 包括：1. 用户自己上传的文件
     *      2. 公开的文件
     *      3. 用户所属组织的文件（包含层级关系）
     *
     * @param userId 用户ID
     * @param orgTagList 用户有效的组织标签列表（包含层级结构）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId IN :userIds OR f.isPublic = true OR f.orgTag IN :orgTags")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userIds") List<String> userIds, @Param("orgTags") List<String> orgTags);

    @Query("SELECT f FROM FileUpload f WHERE f.userId IN :userIds OR f.isPublic = true OR f.orgTag IN :orgTags")
    List<FileUpload> findAccessibleFiles(@Param("userIds") List<String> userIds, @Param("orgTags") List<String> orgTags);
    
    /**
     * 查询用户自己上传的所有文件
     * 
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    List<FileUpload> findByUserIdIn(List<String> userIds);

    List<FileUpload> findByFileMd5In(List<String> md5List);
}
