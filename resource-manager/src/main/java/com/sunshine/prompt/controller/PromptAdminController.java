package com.sunshine.prompt.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.prompt.dto.PromptCreateRequest;
import com.sunshine.prompt.dto.PromptDetailResponse;
import com.sunshine.prompt.dto.PromptEnableRequest;
import com.sunshine.prompt.dto.PromptListItem;
import com.sunshine.prompt.dto.PromptPublishRequest;
import com.sunshine.prompt.dto.PromptRollbackRequest;
import com.sunshine.prompt.dto.PromptUpdateRequest;
import com.sunshine.prompt.dto.PromptVersionItem;
import com.sunshine.prompt.dto.PromptVersionRequest;
import com.sunshine.prompt.service.PromptAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptAdminController {
    private final PromptAdminService promptAdminService;

    @GetMapping
    public R<List<PromptListItem>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Boolean enabled) {
        return R.ok(promptAdminService.list(kind, enabled));
    }

    @GetMapping("/{id}")
    public R<PromptDetailResponse> get(@PathVariable String id) {
        return R.ok(promptAdminService.get(id));
    }

    @PostMapping
    public R<PromptDetailResponse> create(@RequestBody PromptCreateRequest request) {
        return R.ok(promptAdminService.create(request));
    }

    @PutMapping("/{id}")
    public R<PromptDetailResponse> update(@PathVariable String id, @RequestBody PromptUpdateRequest request) {
        return R.ok(promptAdminService.update(id, request));
    }

    @PostMapping("/{id}/versions")
    public R<PromptVersionItem> addVersion(@PathVariable String id, @RequestBody PromptVersionRequest request) {
        return R.ok(promptAdminService.addVersion(id, request));
    }

    @PostMapping("/{id}/publish")
    public R<PromptDetailResponse> publish(@PathVariable String id, @RequestBody(required = false) PromptPublishRequest request) {
        PromptPublishRequest body = request != null ? request : new PromptPublishRequest(null, null, null);
        return R.ok(promptAdminService.publish(id, body));
    }

    @PostMapping("/{id}/rollback")
    public R<PromptDetailResponse> rollback(@PathVariable String id, @RequestBody PromptRollbackRequest request) {
        return R.ok(promptAdminService.rollback(id, request));
    }

    @GetMapping("/{id}/versions")
    public R<List<PromptVersionItem>> listVersions(@PathVariable String id) {
        return R.ok(promptAdminService.listVersions(id));
    }

    @PutMapping("/{id}/enable")
    public R<PromptDetailResponse> enable(@PathVariable String id, @RequestBody PromptEnableRequest request) {
        return R.ok(promptAdminService.setEnabled(id, request));
    }
}
