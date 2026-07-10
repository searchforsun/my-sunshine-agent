package com.sunshine.tool.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.tool.dto.ToolCatalogEntry;
import com.sunshine.tool.service.DbToolCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolCatalogController {

    private final DbToolCatalogService dbToolCatalogService;

    @GetMapping("/catalog")
    public R<List<ToolCatalogEntry>> catalog(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return R.ok(dbToolCatalogService.listCatalog(tenantId, enabledOnly));
    }
}
