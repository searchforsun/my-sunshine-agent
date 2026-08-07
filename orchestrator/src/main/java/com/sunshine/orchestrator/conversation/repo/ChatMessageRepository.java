package com.sunshine.orchestrator.conversation.repo;

import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {

    @Query("SELECT COALESCE(MAX(m.seq), 0) FROM ChatMessageEntity m WHERE m.conversationId = :convId")
    int findMaxSeq(@Param("convId") String conversationId);

    List<ChatMessageEntity> findByConversationIdOrderBySeqAsc(String conversationId);

    Optional<ChatMessageEntity> findTopByConversationIdOrderBySeqDesc(String conversationId);

    @Query(value = """
            SELECT * FROM chat_message
            WHERE conversation_id = :convId
            ORDER BY seq DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ChatMessageEntity> findRecentByConversationIdDesc(
            @Param("convId") String conversationId,
            @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM chat_message
            WHERE conversation_id = :convId AND seq < :beforeSeq
            ORDER BY seq DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ChatMessageEntity> findPageBeforeSeqDesc(
            @Param("convId") String conversationId,
            @Param("beforeSeq") int beforeSeq,
            @Param("limit") int limit);

    void deleteByConversationId(String conversationId);

    /** 会话搜索：返回命中关键词的最新消息正文（content 升序 seq 排列后取首条即最新） */
    @Query(value = """
            SELECT m.conversation_id, m.content
            FROM chat_message m
            WHERE m.conversation_id IN (:convIds)
              AND LOCATE(:keyword, LOWER(m.content)) > 0
            ORDER BY m.seq DESC
            """, nativeQuery = true)
    List<Object[]> findLatestMatchByConversationIds(
            @Param("convIds") List<String> conversationIds,
            @Param("keyword") String keyword);

    long countByConversationIdAndSeqGreaterThan(String conversationId, int seq);

    long countByConversationIdAndSeqLessThan(String conversationId, int seq);
}
