package com.sunshine.orchestrator.context.admin;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.GcResultView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L1SnapshotView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L2StateView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L2UpdateRequest;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L3StatusView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.ReingestResultView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** 运维 Admin：L1/L2/L3 上下文只读/纠错。 */
@RestController
@RequestMapping("/api/admin/context")
@RequiredArgsConstructor
public class ContextAdminController {

    private final ContextAdminService contextAdminService;

    @GetMapping("/l2")
    public Mono<R<List<L2StateView>>> listL2(
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> R.ok(contextAdminService.listL2(userId, tenantId)));
    }

    @PutMapping("/l2/{id}")
    public Mono<R<L2StateView>> updateL2(
            @PathVariable String id,
            @RequestBody L2UpdateRequest body) {
        return ReactiveBlocking.call(() -> R.ok(contextAdminService.updateL2(id, body)));
    }

    @PostMapping("/l2/{id}/void")
    public Mono<R<L2StateView>> voidL2(@PathVariable String id) {
        return ReactiveBlocking.call(() -> R.ok(contextAdminService.voidL2(id)));
    }

    @GetMapping("/l1")
    public Mono<R<L1SnapshotView>> getL1(@RequestParam String convId) {
        return ReactiveBlocking.call(() -> R.ok(contextAdminService.getL1(convId)));
    }

    @GetMapping("/l3/status")
    public Mono<R<L3StatusView>> l3Status(
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> R.ok(contextAdminService.l3Status(userId, tenantId)));
    }

    @PostMapping("/l3/gc")
    public Mono<R<GcResultView>> gc() {
        return ReactiveBlocking.call(() -> R.ok(contextAdminService.runGc()));
    }

    @PostMapping("/l3/reingest")
    public Mono<R<ReingestResultView>> reingest(@RequestParam String convId) {
        return ReactiveBlocking.call(() -> R.ok(contextAdminService.reingest(convId)));
    }
}
