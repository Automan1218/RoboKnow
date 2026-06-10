package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.UserMemoryFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserMemoryFactRepository extends JpaRepository<UserMemoryFact, Long> {

    Optional<UserMemoryFact> findByUserIdAndContentHash(String userId, String contentHash);

    List<UserMemoryFact> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying
    @Query("UPDATE UserMemoryFact f SET f.hitCount = f.hitCount + 1 WHERE f.id = :id")
    void incrementHitCount(@Param("id") Long id);
}
