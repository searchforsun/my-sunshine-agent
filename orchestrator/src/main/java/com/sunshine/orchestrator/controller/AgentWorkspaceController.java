package com.sunshine.orchestrator.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.sandbox.WorkspaceSandboxLifecycle;
import com.sunshine.orchestrator.workspace.dto.CreateWorkspaceRequest;
import com.sunshine.orchestrator.workspace.dto.WorkspaceVO;
import com.sunshine.orchestrator.workspace.entity.AgentWorkspaceEntity;
import com.sunshine.orchestrator.workspace.repo.AgentWorkspaceRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent-workspaces")
@RequiredArgsConstructor
public class AgentWorkspaceController {

    private static final int MAX_CPUS = 4;
    private static final int MAX_MEMORY_MB = 12288;

    private final AgentWorkspaceRepository workspaceRepo;
    private final WorkspaceSandboxLifecycle workspaceSandboxLifecycle;

    @PostMapping
    public R<WorkspaceVO> create(@Valid @RequestBody CreateWorkspaceRequest req,
                                  @RequestHeader("x-user-id") String userId,
                                  @RequestHeader("x-tenant-id") String tenantId) {
        if (req.memoryMb() != null && req.memoryMb() > MAX_MEMORY_MB) {
            throw new BizException(new FixedErrorCode(400, "workspace_hardware_limit",
                    "内存规格超限: memoryMb=" + req.memoryMb() + " (max " + MAX_MEMORY_MB + ")"));
        }
        if (req.cpus() != null && req.cpus().doubleValue() > MAX_CPUS) {
            throw new BizException(new FixedErrorCode(400, "workspace_hardware_limit",
                    "CPU 规格超限: cpus=" + req.cpus() + " (max " + MAX_CPUS + ")"));
        }
        AgentWorkspaceEntity entity = new AgentWorkspaceEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        entity.setName(req.name().strip());
        entity.setRepoUrl(req.repoUrl().strip());
        entity.setRepoBranch(req.repoBranch() != null && !req.repoBranch().isBlank() ? req.repoBranch().strip() : "main");
        entity.setMemoryMb(req.memoryMb() != null ? req.memoryMb() : 2048);
        entity.setCpus(req.cpus() != null ? req.cpus() : new BigDecimal("2.0"));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        workspaceRepo.save(entity);
        return R.ok(WorkspaceVO.from(entity, null));
    }

    @GetMapping
    public R<List<WorkspaceVO>> list(@RequestHeader("x-user-id") String userId,
                                      @RequestHeader("x-tenant-id") String tenantId) {
        List<WorkspaceVO> list = workspaceRepo
                .findByTenantIdAndUserIdAndStatus(tenantId, userId, "active")
                .stream().map(e -> WorkspaceVO.from(e, null)).toList();
        return R.ok(list);
    }

    @DeleteMapping("/{id}")
    public R<Void> destroy(@PathVariable String id,
                            @RequestHeader("x-user-id") String userId,
                            @RequestHeader("x-tenant-id") String tenantId) {
        AgentWorkspaceEntity ws = workspaceRepo.findById(id)
                .orElseThrow(() -> new BizException(new FixedErrorCode(404, "workspace_not_found", "工作区不存在")));
        if (!ws.getUserId().equals(userId) || !ws.getTenantId().equals(tenantId)) {
            throw new BizException(new FixedErrorCode(403, "workspace_forbidden", "无权操作"));
        }
        workspaceSandboxLifecycle.destroyWorkspaceSession(tenantId, id);
        ws.setStatus("archived");
        ws.setUpdatedAt(Instant.now());
        workspaceRepo.save(ws);
        return R.ok();
    }
}
