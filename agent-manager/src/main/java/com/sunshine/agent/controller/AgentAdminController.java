package com.sunshine.agent.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.agent.dto.AgentCatalogEntry;
import com.sunshine.agent.dto.AgentCreateRequest;
import com.sunshine.agent.dto.AgentEnableRequest;
import com.sunshine.agent.dto.AgentUpdateRequest;
import com.sunshine.agent.service.AgentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/experts")
@RequiredArgsConstructor
public class AgentAdminController {
    private final AgentAdminService expertAdminService;

    @GetMapping
    public R<List<AgentCatalogEntry>> list() {
        return R.ok(expertAdminService.listAll());
    }

    @PostMapping
    public R<AgentCatalogEntry> create(@RequestBody AgentCreateRequest request) {
        return R.ok(expertAdminService.create(request));
    }

    @PutMapping("/{id}")
    public R<AgentCatalogEntry> update(@PathVariable String id, @RequestBody AgentUpdateRequest request) {
        return R.ok(expertAdminService.update(id, request));
    }

    @PutMapping("/{id}/enable")
    public R<AgentCatalogEntry> enable(@PathVariable String id, @RequestBody AgentEnableRequest request) {
        return R.ok(expertAdminService.setEnabled(id, request.enabled()));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        expertAdminService.delete(id);
        return R.ok(null);
    }
}
