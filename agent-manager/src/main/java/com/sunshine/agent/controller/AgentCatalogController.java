package com.sunshine.agent.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.agent.dto.AgentCatalogEntry;
import com.sunshine.agent.dto.AgentCatalogIndexEntry;
import com.sunshine.agent.exception.AgentErrorCode;
import com.sunshine.agent.service.AgentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentCatalogController {
    private final AgentAdminService agentAdminService;

    @GetMapping("/catalog")
    public R<Void> catalogRemoved() {
        throw new BizException(CommonErrorCode.GONE);
    }

    @GetMapping("/catalog/index")
    public R<List<AgentCatalogIndexEntry>> catalogIndex() {
        return R.ok(agentAdminService.listCatalogIndex());
    }

    @GetMapping("/{id}/catalog")
    public R<AgentCatalogEntry> catalogDetail(@PathVariable String id) {
        return agentAdminService.findCatalogEntry(id)
                .map(R::ok)
                .orElseThrow(() -> new BizException(AgentErrorCode.AGENT_NOT_FOUND));
    }
}
