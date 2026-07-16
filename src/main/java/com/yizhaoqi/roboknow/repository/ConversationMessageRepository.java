package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findByConvIdOrderBySeqAsc(String convId);

    long countByConvId(String convId);
}
