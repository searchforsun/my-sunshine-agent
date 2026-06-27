package com.sunshine.orchestrator.audit;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.audit.entity.ChatAuditLogEntity;
import com.sunshine.orchestrator.audit.repo.ChatAuditLogRepository;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/recent")
    public Mono<R<List<ChatAuditLogEntity>>> recent() {
        return ReactiveBlocking.call(() -> R.ok(auditLogRepository.findTop20ByOrderByCreatedAtDesc()));
    }

    @GetMapping("/sub-runs")
    public Mono<R<List<ChatAuditLogEntity>>> subRuns(@RequestParam String messageId) {
        return ReactiveBlocking.call(() -> R.ok(
                auditLogRepository.findByMessageIdAndEventTypeOrderByCreatedAtDesc(
                        messageId, "sub_agent_run")));
    }

    @GetMapping("/tool-calls")
    public Mono<R<List<ChatAuditLogEntity>>> toolCalls(
            @RequestParam(required = false) String messageId,
            @RequestParam(required = false) String conversationId) {
        return ReactiveBlocking.call(() -> {
            if (messageId != null && !messageId.isBlank()) {
                return R.ok(auditLogRepository.findByMessageIdAndEventTypeOrderByCreatedAtDesc(
                        messageId, "tool.call"));
            }
            if (conversationId != null && !conversationId.isBlank()) {
                return R.ok(auditLogRepository.findByConversationIdAndEventTypeOrderByCreatedAtDesc(
                        conversationId, "tool.call"));
            }
            return R.ok(List.<ChatAuditLogEntity>of());
        });
    }
}
