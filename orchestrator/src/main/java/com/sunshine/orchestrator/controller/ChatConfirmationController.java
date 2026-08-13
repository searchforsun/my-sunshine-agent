package com.sunshine.orchestrator.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.hitl.WorkflowNodeRecoveryService;
import com.sunshine.orchestrator.model.ConfirmToolRequest;
import com.sunshine.orchestrator.model.ConfirmWorkflowNodeRecoveryRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** HITL / Workflow 节点 Recovery — 与 SSE 主路径分离 */
@RestController
public class ChatConfirmationController {

    @Autowired(required = false)
    private HitlConfirmationService hitlConfirmationService;

    @Autowired(required = false)
    private WorkflowNodeRecoveryService workflowNodeRecoveryService;

    @PostMapping("/chat/confirm-tool")
    public Mono<Map<String, Object>> confirmTool(@RequestBody ConfirmToolRequest request,
                                                  @RequestHeader(value = "x-user-id", required = false) String userId) {
        if (hitlConfirmationService == null) {
            return Mono.error(new BizException(OrchestratorErrorCode.HITL_DISABLED));
        }
        if (request == null || !StringUtils.hasText(request.token())) {
            return Mono.error(new BizException(OrchestratorErrorCode.CONFIRM_TOKEN_REQUIRED));
        }
        return Mono.fromCallable(() -> {
                    boolean ok = hitlConfirmationService.confirm(request.token(), request.approved(), userId);
                    return Map.<String, Object>of("accepted", ok);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/chat/workflow-node-recovery")
    public Mono<Map<String, Object>> confirmWorkflowNodeRecovery(
            @RequestBody ConfirmWorkflowNodeRecoveryRequest request) {
        if (workflowNodeRecoveryService == null) {
            return Mono.error(new BizException(OrchestratorErrorCode.HITL_DISABLED));
        }
        if (request == null || !StringUtils.hasText(request.token()) || !StringUtils.hasText(request.action())) {
            return Mono.error(new BizException(OrchestratorErrorCode.CONFIRM_TOKEN_REQUIRED));
        }
        return Mono.fromCallable(() -> {
                    boolean ok = workflowNodeRecoveryService.confirm(request.token(), request.action());
                    return Map.<String, Object>of("accepted", ok);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
