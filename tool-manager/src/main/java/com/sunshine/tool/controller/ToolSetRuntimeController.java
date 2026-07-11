package com.sunshine.tool.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.tool.admin.ToolSetKind;
import com.sunshine.tool.admin.ToolSetMemberService;
import com.sunshine.common.tool.admin.ToolSetToolIdsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools/sets")
@RequiredArgsConstructor
public class ToolSetRuntimeController {

    private final ToolSetMemberService toolSetMemberService;

    @GetMapping("/{kind}/tool-ids")
    public R<ToolSetToolIdsResponse> toolIds(
            @PathVariable String kind,
            @RequestParam(required = false) String tenantId) {
        return R.ok(toolSetMemberService.toolIds(ToolSetKind.fromPath(kind), tenantId));
    }
}
