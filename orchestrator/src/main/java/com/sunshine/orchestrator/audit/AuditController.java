package com.sunshine.orchestrator.audit;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.audit.entity.ChatAuditLogEntity;
import com.sunshine.orchestrator.audit.repo.ChatAuditLogRepository;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardAuditService;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardAuditView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final ChatAuditLogRepository auditLogRepository;
    private final ReactTaskBoardAuditService taskBoardAuditService;

    @GetMapping("/recent")
    public Mono<R<List<ChatAuditLogEntity>>> recent(
            @RequestHeader(value = "x-user-id", required = false) String userId) {
        return ReactiveBlocking.call(() -> {
            if (userId != null && !userId.isBlank()) {
                return R.ok(auditLogRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId));
            }
            return R.ok(auditLogRepository.findTop20ByOrderByCreatedAtDesc());
        });
    }

    @GetMapping("/sub-runs")
    public Mono<R<List<ChatAuditLogEntity>>> subRuns(
            @RequestParam String messageId,
            @RequestHeader(value = "x-user-id", required = false) String userId) {
        return ReactiveBlocking.call(() -> {
            List<ChatAuditLogEntity> logs = auditLogRepository
                    .findByMessageIdAndEventTypeOrderByCreatedAtDesc(messageId, "sub_agent_run");
            return R.ok(filtersByUserId(logs, userId));
        });
    }

    @GetMapping("/tool-calls")
    public Mono<R<List<ChatAuditLogEntity>>> toolCalls(
            @RequestParam(required = false) String messageId,
            @RequestParam(required = false) String conversationId,
            @RequestHeader(value = "x-user-id", required = false) String userId) {
        return ReactiveBlocking.call(() -> {
            if (messageId != null && !messageId.isBlank()) {
                List<ChatAuditLogEntity> logs = auditLogRepository
                        .findByMessageIdAndEventTypeOrderByCreatedAtDesc(messageId, "tool.call");
                return R.ok(filtersByUserId(logs, userId));
            }
            if (conversationId != null && !conversationId.isBlank()) {
                List<ChatAuditLogEntity> logs = auditLogRepository
                        .findByConversationIdAndEventTypeOrderByCreatedAtDesc(conversationId, "tool.call");
                return R.ok(filtersByUserId(logs, userId));
            }
            return R.ok(List.<ChatAuditLogEntity>of());
        });
    }

    @GetMapping("/taskboard/{messageId}")
    public Mono<R<ReactTaskBoardAuditView>> taskboard(@PathVariable String messageId) {
        return ReactiveBlocking.call(() -> R.ok(
                taskBoardAuditService.findByMessageId(messageId)
                        .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND))));
    }

    private List<ChatAuditLogEntity> filtersByUserId(List<ChatAuditLogEntity> logs, String userId) {
        if (userId == null || userId.isBlank()) {
            return logs;
        }
        return logs.stream()
                .filter(log -> userId.equals(log.getUserId()))
                .toList();
    }
}
