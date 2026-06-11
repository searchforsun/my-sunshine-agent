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

    void deleteByConversationId(String conversationId);

    long countByConversationIdAndSeqGreaterThan(String conversationId, int seq);
}
