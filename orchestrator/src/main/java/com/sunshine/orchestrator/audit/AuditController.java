package com.sunshine.orchestrator.audit;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.audit.entity.ChatAuditLogEntity;
import com.sunshine.orchestrator.audit.repo.ChatAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final ChatAuditLogRepository auditLogRepository;

    @GetMapping("/recent")
    public R<List<ChatAuditLogEntity>> recent() {
        return R.ok(auditLogRepository.findTop20ByOrderByCreatedAtDesc());
    }
}
