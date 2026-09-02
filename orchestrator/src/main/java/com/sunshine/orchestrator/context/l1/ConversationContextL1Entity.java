package com.sunshine.orchestrator.context.l1;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** L1 会话派生：mid 答案映射 + far 摘要 + 窗口元数据 */
@Entity
@Table(name = "conversation_context_l1")
@Getter
@Setter
public class ConversationContextL1Entity {

    @Id
    @Column(name = "conv_id", length = 32)
    private String convId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    /** JSON map msgId -> answer summary */
    @Column(name = "mid_answers", columnDefinition = "MEDIUMTEXT")
    private String midAnswers;

    @Column(name = "far_summary", columnDefinition = "MEDIUMTEXT")
    private String farSummary;

    /** JSON array of messageIds 已退役（压缩点之前）；Near 起点 = 该边界之后 */
    @Column(name = "far_folded_msg_ids", columnDefinition = "MEDIUMTEXT")
    private String farFoldedMsgIds;

    /**
     * JSON array of messageIds 已实际折叠进 far_summary（压缩点边界的子集）。
     * 同步推进压缩点（§5.5 ① / §8.2）仅移动 {@code far_folded_msg_ids} 边界（零 LLM），
     * 被退役但尚未折叠的轮次留在两者差集，由写路径异步补折叠。
     * {@code null} = 存量行兼容：视为与 {@code far_folded_msg_ids} 一致（旧语义全部已折叠）。
     */
    @Column(name = "far_summarized_msg_ids", columnDefinition = "MEDIUMTEXT")
    private String farSummarizedMsgIds;

    @Column(name = "near_n", nullable = false)
    private int nearN = 8;

    @Column(name = "mid_n", nullable = false)
    private int midN = 8;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
