package com.sunshine.orchestrator.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.common.sandbox.FsNodeDto;
import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.orchestrator.sandbox.WorkspaceSandboxLifecycle;
import com.sunshine.orchestrator.sandbox.WorkspaceGitService;
import com.sunshine.orchestrator.sandbox.WorkspaceCheckoutService;
import com.sunshine.orchestrator.sandbox.WorkspaceSandboxStore;
import com.sunshine.orchestrator.sandbox.WorkspaceSandboxBinding;
import com.sunshine.orchestrator.sandbox.SandboxWorkspaceService;
import com.sunshine.orchestrator.workspace.dto.CreateWorkspaceRequest;
import com.sunshine.orchestrator.workspace.dto.WorkspacePageDto;
import com.sunshine.orchestrator.workspace.dto.WorkspaceVO;
import com.sunshine.orchestrator.workspace.entity.AgentWorkspaceEntity;
import com.sunshine.orchestrator.workspace.entity.WorkspaceProjectGuideEntity;
import com.sunshine.orchestrator.workspace.repo.AgentWorkspaceRepository;
import com.sunshine.orchestrator.workspace.repo.WorkspaceProjectGuideRepository;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/agent-workspaces")
@RequiredArgsConstructor
public class AgentWorkspaceController {

    private static final int MAX_PROJECT_GUIDE_CHARS = 64 * 1024;

    private final AgentWorkspaceRepository workspaceRepo;
    private final WorkspaceSandboxLifecycle workspaceSandboxLifecycle;
    private final WorkspaceGitService workspaceGitService;
    private final WorkspaceCheckoutService workspaceCheckoutService;
    private final WorkspaceSandboxStore workspaceStore;
    private final SandboxWorkspaceService sandboxWorkspaceService;
    private final ChatConversationRepository conversationRepo;
    private final WorkspaceProjectGuideRepository projectGuideRepo;
    private final com.sunshine.orchestrator.config.AgentSandboxProperties sandboxProperties;

    @PostMapping
    public Mono<R<WorkspaceVO>> create(@Valid @RequestBody CreateWorkspaceRequest req,
                                        @RequestHeader("x-user-id") String userId,
                                        @RequestHeader("x-tenant-id") String tenantId) {
        return ReactiveBlocking.call(() -> createAndProvision(req, userId, tenantId));
    }

    /** 同步落库 + provision（clone mirror）；失败回滚删除实体，成功才标 active 进列表 */
    private R<WorkspaceVO> createAndProvision(CreateWorkspaceRequest req, String userId, String tenantId) {
        int[] resolved;
        try {
            resolved = sandboxProperties.validateAndResolve("full", req.memoryMb(), req.cpus());
        } catch (IllegalArgumentException e) {
            throw new BizException(new FixedErrorCode(400, "workspace_hardware_invalid", e.getMessage()));
        }
        int memoryMb = resolved[0];
        BigDecimal cpus = BigDecimal.valueOf(resolved[1] / 10.0);
        AgentWorkspaceEntity entity = new AgentWorkspaceEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        String name = (req.name() != null && !req.name().isBlank())
                ? req.name().strip()
                : extractRepoName(req.repoUrl().strip());
        entity.setName(name);
        entity.setRepoUrl(req.repoUrl().strip());
        // repoBranch 留空 = clone 拉取远程默认主分支；显式填值才指定 --branch
        entity.setRepoBranch(req.repoBranch() != null && !req.repoBranch().isBlank() ? req.repoBranch().strip() : "");
        entity.setMemoryMb(memoryMb);
        entity.setCpus(cpus);
        // 拉取完成前不进列表（list 仅 active）；失败硬删除，避免半成品工作区
        entity.setStatus("provisioning");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        workspaceRepo.saveAndFlush(entity);
        try {
            workspaceSandboxLifecycle.ensureWorkspaceSession(entity.getId(), userId, tenantId);
            String cloneState = workspaceStore.find(tenantId, entity.getId())
                    .map(WorkspaceSandboxBinding::cloneState)
                    .orElse(null);
            if (!"done".equals(cloneState)) {
                String detail = cloneState != null && cloneState.startsWith("failed:")
                        ? cloneState.substring("failed:".length()).strip()
                        : "代码拉取未完成";
                rollbackFailedCreate(entity);
                throw new BizException(new FixedErrorCode(502, "workspace_clone_failed",
                        "工作区代码拉取失败: " + detail));
            }
            entity.setStatus("active");
            entity.setUpdatedAt(Instant.now());
            workspaceRepo.saveAndFlush(entity);
            log.info("[AgentWorkspace] provisioned ws={} clone=done", entity.getId());
            return R.ok(WorkspaceVO.from(entity, cloneState));
        } catch (BizException e) {
            // clone 失败路径已 rollback；其它 BizException 也确保不留 provisioning 行
            if (workspaceRepo.existsById(entity.getId())) {
                rollbackFailedCreate(entity);
            }
            throw e;
        } catch (Exception e) {
            log.warn("[AgentWorkspace] provision failed ws={}: {}", entity.getId(), e.getMessage());
            rollbackFailedCreate(entity);
            throw new BizException(new FixedErrorCode(502, "workspace_clone_failed",
                    "工作区代码拉取失败: " + truncateMsg(e.getMessage(), 160)));
        }
    }

    /** 创建阶段失败：销毁沙箱/裸库并硬删除实体（非 archive） */
    private void rollbackFailedCreate(AgentWorkspaceEntity entity) {
        try {
            workspaceSandboxLifecycle.destroyWorkspaceSession(entity.getTenantId(), entity.getId());
        } catch (Exception e) {
            log.warn("[AgentWorkspace] rollback destroy session failed ws={}: {}", entity.getId(), e.getMessage());
        }
        try {
            if (workspaceRepo.existsById(entity.getId())) {
                workspaceRepo.deleteById(entity.getId());
                workspaceRepo.flush();
            }
            log.info("[AgentWorkspace] rollback deleted ws={}", entity.getId());
        } catch (Exception e) {
            log.warn("[AgentWorkspace] rollback delete entity failed ws={}: {}", entity.getId(), e.getMessage());
        }
    }

    private static String truncateMsg(String s, int maxLen) {
        if (s == null || s.isBlank()) return "未知错误";
        String t = s.strip();
        return t.length() <= maxLen ? t : t.substring(0, maxLen) + "...";
    }

    @GetMapping
    public R<?> list(@RequestHeader("x-user-id") String userId,
                     @RequestHeader("x-tenant-id") String tenantId,
                     @RequestParam(value = "limit", required = false) Integer limit,
                     @RequestParam(value = "offset", defaultValue = "0") int offset) {
        if (limit == null) {
            List<WorkspaceVO> list = workspaceRepo
                    .findByTenantIdAndUserIdAndStatus(tenantId, userId, "active")
                    .stream().map(e -> toWorkspaceVo(tenantId, e)).toList();
            return R.ok(list);
        }
        int pageSize = Math.max(1, Math.min(limit, 100));
        int off = Math.max(0, offset);
        int page = off / pageSize;
        var rows = workspaceRepo.findByTenantIdAndUserIdAndStatusOrderByUpdatedAtDesc(
                tenantId, userId, "active", PageRequest.of(page, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<WorkspaceVO> items = (hasMore ? rows.subList(0, pageSize) : rows).stream()
                .map(e -> toWorkspaceVo(tenantId, e))
                .toList();
        return R.ok(WorkspacePageDto.builder().items(items).hasMore(hasMore).build());
    }

    private WorkspaceVO toWorkspaceVo(String tenantId, AgentWorkspaceEntity e) {
        String cloneState = workspaceStore.find(tenantId, e.getId())
                .map(WorkspaceSandboxBinding::cloneState)
                .orElse(null);
        return WorkspaceVO.from(e, cloneState);
    }

    @DeleteMapping("/{id}")
    public R<Void> destroy(@PathVariable String id,
                            @RequestHeader("x-user-id") String userId,
                            @RequestHeader("x-tenant-id") String tenantId) {
        AgentWorkspaceEntity ws = requireOwned(id, userId, tenantId);
        workspaceSandboxLifecycle.destroyWorkspaceSession(tenantId, id);
        conversationRepo.deleteByWorkspaceId(id);
        projectGuideRepo.deleteById(id);
        ws.setStatus("archived");
        ws.setUpdatedAt(Instant.now());
        workspaceRepo.save(ws);
        return R.ok();
    }

    // ===== Git 操作（均异步执行在 boundedElastic，避免 reactor 线程 block） =====

    @GetMapping("/{id}/branches")
    public Mono<R<List<Map<String, Object>>>> listBranches(@PathVariable String id,
                                                     @RequestHeader("x-user-id") String userId,
                                                     @RequestHeader("x-tenant-id") String tenantId) {
        return Mono.fromCallable(() -> R.ok(workspaceGitService.listBranches(id, userId, tenantId)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/branches")
    public Mono<R<Void>> createBranch(@PathVariable String id,
                                @RequestHeader("x-user-id") String userId,
                                @RequestHeader("x-tenant-id") String tenantId,
                                @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String from = body.get("from");
        if (name == null || name.isBlank()) {
            throw new BizException(new FixedErrorCode(400, "branch_name_required", "分支名不能为空"));
        }
        String branch = name.strip();
        return Mono.fromRunnable(() -> workspaceGitService.createBranch(id, userId, tenantId, branch, from))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .thenReturn(R.ok());
    }

    @GetMapping("/{id}/git/status")
    public Mono<R<Map<String, Object>>> gitStatus(@PathVariable String id,
                                            @RequestParam("checkoutId") String checkoutId,
                                            @RequestHeader("x-user-id") String userId,
                                            @RequestHeader("x-tenant-id") String tenantId) {
        return Mono.fromCallable(() -> R.ok(workspaceGitService.gitStatus(id, checkoutId, userId, tenantId)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** git diff：无 path → 改动文件摘要（path/add/del/status）；有 path → 单文件结构化 diff 详情 */
    @GetMapping("/{id}/git/diff")
    public Mono<R<Object>> gitDiff(@PathVariable String id,
                                   @RequestParam("checkoutId") String checkoutId,
                                   @RequestParam(value = "path", required = false) String path,
                                   @RequestHeader("x-user-id") String userId,
                                   @RequestHeader("x-tenant-id") String tenantId) {
        return Mono.fromCallable(() -> {
            if (path != null && !path.isBlank()) {
                return R.ok((Object) workspaceGitService.gitDiffDetail(id, checkoutId, userId, tenantId, path.strip()));
            }
            return R.ok((Object) workspaceGitService.gitDiffSummary(id, checkoutId, userId, tenantId));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/git/stage")
    public Mono<R<Void>> gitStage(@PathVariable String id,
                            @RequestParam("checkoutId") String checkoutId,
                            @RequestHeader("x-user-id") String userId,
                            @RequestHeader("x-tenant-id") String tenantId,
                            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> files = body != null ? (List<String>) body.get("files") : null;
        boolean all = body != null && Boolean.TRUE.equals(body.get("all"));
        return Mono.fromRunnable(() -> workspaceGitService.gitStage(id, checkoutId, userId, tenantId, files, all))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .thenReturn(R.ok());
    }

    /** git revert：回退指定文件改动到 HEAD（未跟踪文件删除） */
    @PostMapping("/{id}/git/revert")
    public Mono<R<Void>> gitRevert(@PathVariable String id,
                            @RequestParam("checkoutId") String checkoutId,
                            @RequestHeader("x-user-id") String userId,
                            @RequestHeader("x-tenant-id") String tenantId,
                            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> files = body != null ? (List<String>) body.get("files") : null;
        if (files == null || files.isEmpty()) {
            throw new BizException(new FixedErrorCode(400, "revert_files_required", "回退文件不能为空"));
        }
        return Mono.fromRunnable(() -> workspaceGitService.gitRevert(id, checkoutId, userId, tenantId, files))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .thenReturn(R.ok());
    }

    /** git unstage：撤回暂存（仅清暂存区，保留工作区改动） */
    @PostMapping("/{id}/git/unstage")
    public Mono<R<Void>> gitUnstage(@PathVariable String id,
                            @RequestParam("checkoutId") String checkoutId,
                            @RequestHeader("x-user-id") String userId,
                            @RequestHeader("x-tenant-id") String tenantId,
                            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> files = body != null ? (List<String>) body.get("files") : null;
        if (files == null || files.isEmpty()) {
            throw new BizException(new FixedErrorCode(400, "unstage_files_required", "撤回文件不能为空"));
        }
        return Mono.fromRunnable(() -> workspaceGitService.gitUnstage(id, checkoutId, userId, tenantId, files))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .thenReturn(R.ok());
    }

    @PostMapping("/{id}/git/commit")
    public Mono<R<Void>> gitCommit(@PathVariable String id,
                             @RequestParam("checkoutId") String checkoutId,
                             @RequestHeader("x-user-id") String userId,
                             @RequestHeader("x-tenant-id") String tenantId,
                             @RequestBody Map<String, String> body) {
        String message = body != null ? body.get("message") : null;
        if (message == null || message.isBlank()) {
            throw new BizException(new FixedErrorCode(400, "commit_message_required", "commit 信息不能为空"));
        }
        String msg = message.strip();
        return Mono.fromRunnable(() -> workspaceGitService.gitCommit(id, checkoutId, userId, tenantId, msg))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .thenReturn(R.ok());
    }

    @PostMapping("/{id}/git/push")
    public Mono<R<Void>> gitPush(@PathVariable String id,
                           @RequestParam("checkoutId") String checkoutId,
                           @RequestHeader("x-user-id") String userId,
                           @RequestHeader("x-tenant-id") String tenantId) {
        return Mono.<Void>fromRunnable(() -> workspaceGitService.gitPush(id, checkoutId, userId, tenantId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .thenReturn(R.<Void>ok())
                .onErrorMap(e -> e instanceof BizException ? e : new BizException(new FixedErrorCode(400,
                        "git_push_failed", "推送失败: " + e.getMessage())));
    }

    /** git pull：从远程拉取最新代码（作用于会话工作目录） */
    @PostMapping("/{id}/git/pull")
    public Mono<R<Map<String, Object>>> gitPull(@PathVariable String id,
                                          @RequestParam("checkoutId") String checkoutId,
                                          @RequestHeader("x-user-id") String userId,
                                          @RequestHeader("x-tenant-id") String tenantId) {
        return Mono.fromCallable(() -> R.ok(workspaceGitService.gitPull(id, checkoutId, userId, tenantId)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .onErrorMap(e -> e instanceof BizException ? e : new BizException(new FixedErrorCode(500,
                        "git_pull_failed", "拉取失败: " + e.getMessage())));
    }

    /** 同步工作区代码：已克隆则 git pull，未克隆则重新 clone；用于刷新/重试 */
    @PostMapping("/{id}/sync")
    public Mono<R<Map<String, Object>>> syncWorkspace(@PathVariable String id,
                                                        @RequestHeader("x-user-id") String userId,
                                                        @RequestHeader("x-tenant-id") String tenantId) {
        requireOwned(id, userId, tenantId);
        return Mono.fromFuture(() -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            return workspaceSandboxLifecycle.syncWorkspaceCode(id, userId);
        })).<R<Map<String, Object>>>map(R::ok)
          .timeout(java.time.Duration.ofMinutes(10))
          .onErrorMap(e -> {
              Throwable root = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                      ? e.getCause() : e;
              if (root instanceof BizException) return (BizException) root;
              if (e instanceof java.util.concurrent.TimeoutException)
                  return new BizException(new FixedErrorCode(500, "workspace_sync_timeout", "同步超时"));
              return new BizException(new FixedErrorCode(500, "workspace_sync_failed",
                      "同步失败: " + root.getMessage()));
          });
    }

    // ===== Checkout 管理（会话工作目录：/workspace/{checkoutId}，分支与目录一一对应） =====
    // 注意：内部会触发 ensureWorkspaceSession（含 sandboxClient.createSession 阻塞调用），
    // 必须在 boundedElastic 线程执行，避免 reactor 线程 block() 报错。

    @GetMapping("/{id}/checkouts")
    public Mono<R<List<WorkspaceCheckoutService.CheckoutInfo>>> listCheckouts(
            @PathVariable String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader("x-tenant-id") String tenantId) {
        return ReactiveBlocking.call(() -> {
            requireOwned(id, userId, tenantId);
            return R.ok(workspaceCheckoutService.listCheckouts(id, userId, tenantId));
        });
    }

    /** 新建 worktree checkout（懒创建）；返回新 checkoutId */
    @PostMapping("/{id}/checkouts")
    public Mono<R<String>> createCheckout(@PathVariable String id,
                                          @RequestBody Map<String, String> body,
                                          @RequestHeader("x-user-id") String userId,
                                          @RequestHeader("x-tenant-id") String tenantId) {
        return ReactiveBlocking.call(() -> {
            requireOwned(id, userId, tenantId);
            String branch = body != null ? body.get("branch") : null;
            if (branch == null || branch.isBlank()) {
                throw new BizException(new FixedErrorCode(400, "branch_name_required", "分支名不能为空"));
            }
            String from = body != null ? body.get("from") : null;
            return R.ok(workspaceCheckoutService.createWorktree(id, userId, tenantId, branch.strip(), from));
        });
    }

    /** 按分支名幂等确保 checkout 存在（已有复用、无则懒创建）；返回 checkoutId */
    @PostMapping("/{id}/checkouts/ensure")
    public Mono<R<String>> ensureCheckout(@PathVariable String id,
                                          @RequestBody Map<String, String> body,
                                          @RequestHeader("x-user-id") String userId,
                                          @RequestHeader("x-tenant-id") String tenantId) {
        return ReactiveBlocking.call(() -> {
            requireOwned(id, userId, tenantId);
            String branch = body != null ? body.get("branch") : null;
            if (branch == null || branch.isBlank()) {
                throw new BizException(new FixedErrorCode(400, "branch_name_required", "分支名不能为空"));
            }
            return R.ok(workspaceCheckoutService.ensureCheckout(id, userId, tenantId, branch.strip()));
        });
    }

    /** 删除 worktree checkout（用户显式触发） */
    @DeleteMapping("/{id}/checkouts/{checkoutId}")
    public Mono<R<Void>> removeCheckout(@PathVariable String id,
                                        @PathVariable String checkoutId,
                                        @RequestHeader("x-user-id") String userId,
                                        @RequestHeader("x-tenant-id") String tenantId) {
        return ReactiveBlocking.call(() -> {
            requireOwned(id, userId, tenantId);
            workspaceCheckoutService.removeWorktree(id, userId, tenantId, checkoutId);
            return R.ok();
        });
    }

    /** 从 Git URL 中提取仓库名，如 https://github.com/user/my-project.git → my-project */
    private static String extractRepoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return "workspace";
        String s = repoUrl.strip();
        // 去掉末尾 .git 和斜杠
        s = s.replaceFirst("\\.git/?$", "").replaceFirst("/+$", "");
        Matcher m = Pattern.compile("/([^/]+)$").matcher(s);
        return m.find() ? m.group(1) : "workspace";
    }

    // ===== 项目规范（类 CLAUDE.md，用户手动维护） =====

    /** 读取项目规范；未维护时返回空串 */
    @GetMapping("/{id}/project-guide")
    public R<Map<String, Object>> getProjectGuide(@PathVariable String id,
                                                   @RequestHeader("x-user-id") String userId,
                                                   @RequestHeader("x-tenant-id") String tenantId) {
        requireOwned(id, userId, tenantId);
        WorkspaceProjectGuideEntity guide = projectGuideRepo.findById(id).orElse(null);
        return R.ok(Map.of(
                "content", guide != null && guide.getContent() != null ? guide.getContent() : "",
                "updatedAt", guide != null && guide.getUpdatedAt() != null ? guide.getUpdatedAt().toString() : ""));
    }

    /** 保存项目规范（Markdown 全文覆盖） */
    @PutMapping("/{id}/project-guide")
    public R<Void> saveProjectGuide(@PathVariable String id,
                                    @RequestBody Map<String, String> body,
                                    @RequestHeader("x-user-id") String userId,
                                    @RequestHeader("x-tenant-id") String tenantId) {
        AgentWorkspaceEntity ws = requireOwned(id, userId, tenantId);
        String content = body != null && body.get("content") != null ? body.get("content").strip() : "";
        if (content.length() > MAX_PROJECT_GUIDE_CHARS) {
            throw new BizException(new FixedErrorCode(400, "project_guide_too_large",
                    "项目规范超长: max " + MAX_PROJECT_GUIDE_CHARS + " chars"));
        }
        Instant now = Instant.now();
        WorkspaceProjectGuideEntity guide = projectGuideRepo.findById(id).orElse(null);
        if (guide == null) {
            guide = new WorkspaceProjectGuideEntity();
            guide.setWorkspaceId(id);
            guide.setTenantId(ws.getTenantId());
            guide.setUserId(ws.getUserId());
            guide.setCreatedAt(now);
        }
        guide.setContent(content);
        guide.setUpdatedBy(userId);
        guide.setUpdatedAt(now);
        projectGuideRepo.save(guide);
        return R.ok();
    }

    /** 校验工作区存在且归属当前用户/租户，返回实体。 */
    private AgentWorkspaceEntity requireOwned(String id, String userId, String tenantId) {
        AgentWorkspaceEntity ws = workspaceRepo.findById(id)
                .orElseThrow(() -> new BizException(new FixedErrorCode(404, "workspace_not_found", "工作区不存在")));
        if (!ws.getUserId().equals(userId) || !ws.getTenantId().equals(tenantId)) {
            throw new BizException(new FixedErrorCode(403, "workspace_forbidden", "无权操作"));
        }
        return ws;
    }

    // ===== 工作区文件浏览（无需 conversationId） =====

    @GetMapping("/{id}/sandbox/workspace")
    public Mono<FsNodeDto.FsListResponse> listFiles(
            @PathVariable String id,
            @RequestParam(value = "path", required = false, defaultValue = "/workspace") String path,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> sandboxWorkspaceService.listByWorkspace(id, tenantId, path));
    }

    @GetMapping("/{id}/sandbox/workspace/index")
    public Mono<FsNodeDto.FsIndexResponse> listFileIndex(
            @PathVariable String id,
            @RequestParam(value = "path", required = false, defaultValue = "/workspace") String path,
            @RequestParam(value = "maxDepth", required = false, defaultValue = "64") int maxDepth,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> sandboxWorkspaceService.indexByWorkspace(id, tenantId, path, maxDepth));
    }

    @GetMapping("/{id}/sandbox/workspace/content")
    public Mono<FsContentDto> readFile(
            @PathVariable String id,
            @RequestParam("path") String path,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> sandboxWorkspaceService.contentByWorkspace(id, tenantId, path, offset));
    }
}
