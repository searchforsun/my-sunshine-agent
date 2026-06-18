package com.sunshine.tool.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.tool.dto.ToolCatalogEntry;
import com.sunshine.tool.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolCatalogController {

    private final ToolRegistry toolRegistry;

    @GetMapping("/catalog")
    public R<List<ToolCatalogEntry>> catalog() {
        return R.ok(toolRegistry.listCatalog());
    }
}
