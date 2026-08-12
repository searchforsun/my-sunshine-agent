package com.sunshine.orchestrator.conversation.repo;

import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatConversationRepository extends JpaRepository<ChatConversationEntity, String> {

    List<ChatConversationEntity> findByUserIdAndTenantIdOrderByUpdatedAtDesc(
            String userId, String tenantId);

    /** 对话侧栏：排除 task（含 kind 为空的历史 chat） */
    @Query("""
            SELECT c FROM ChatConversationEntity c
            WHERE c.userId = :userId AND c.tenantId = :tenantId
              AND (c.kind IS NULL OR c.kind <> 'task')
            ORDER BY c.updatedAt DESC
            """)
    List<ChatConversationEntity> findChatPage(
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            Pageable pageable);

    /** 任务侧栏：某工作区下的 task 会话 */
    @Query("""
            SELECT c FROM ChatConversationEntity c
            WHERE c.userId = :userId AND c.tenantId = :tenantId
              AND c.kind = 'task' AND c.workspaceId = :workspaceId
            ORDER BY c.updatedAt DESC
            """)
    List<ChatConversationEntity> findTaskPageByWorkspace(
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("workspaceId") String workspaceId,
            Pageable pageable);

    @Query("""
            SELECT c FROM ChatConversationEntity c
            WHERE c.userId = :userId AND c.tenantId = :tenantId
              AND LOCATE(:keyword, LOWER(c.title)) > 0
            ORDER BY c.updatedAt DESC
            """)
    List<ChatConversationEntity> searchByTitle(
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword);

    @Query(value = """
            SELECT DISTINCT c.*
            FROM chat_conversation c
            JOIN chat_message m ON m.conversation_id = c.id
            WHERE c.user_id = :userId AND c.tenant_id = :tenantId
              AND LOCATE(:keyword, LOWER(m.content)) > 0
            ORDER BY c.updated_at DESC
            """, nativeQuery = true)
    List<ChatConversationEntity> searchByMessageContent(
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword);

    @Transactional
    void deleteByWorkspaceId(String workspaceId);
}
