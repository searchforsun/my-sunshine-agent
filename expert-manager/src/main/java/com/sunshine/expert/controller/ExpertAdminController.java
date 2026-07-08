package com.sunshine.expert.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.expert.dto.ExpertCatalogEntry;
import com.sunshine.expert.dto.ExpertCreateRequest;
import com.sunshine.expert.dto.ExpertEnableRequest;
import com.sunshine.expert.dto.ExpertUpdateRequest;
import com.sunshine.expert.service.ExpertAdminService;
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
public class ExpertAdminController {
    private final ExpertAdminService expertAdminService;

    @GetMapping
    public R<List<ExpertCatalogEntry>> list() {
        return R.ok(expertAdminService.listAll());
    }

    @PostMapping
    public R<ExpertCatalogEntry> create(@RequestBody ExpertCreateRequest request) {
        return R.ok(expertAdminService.create(request));
    }

    @PutMapping("/{id}")
    public R<ExpertCatalogEntry> update(@PathVariable String id, @RequestBody ExpertUpdateRequest request) {
        return R.ok(expertAdminService.update(id, request));
    }

    @PutMapping("/{id}/enable")
    public R<ExpertCatalogEntry> enable(@PathVariable String id, @RequestBody ExpertEnableRequest request) {
        return R.ok(expertAdminService.setEnabled(id, request.enabled()));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        expertAdminService.delete(id);
        return R.ok(null);
    }
}
