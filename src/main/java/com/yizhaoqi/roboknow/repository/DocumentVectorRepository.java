package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.DocumentVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5);

    /** Returns only child chunks (isParent = false) for a given file. Used by VectorizationService. */
    List<DocumentVector> findByFileMd5AndIsParentFalse(String fileMd5);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);
}
