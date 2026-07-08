package com.sunshine.expert.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.expert.dto.ExpertCatalogEntry;
import com.sunshine.expert.dto.ExpertCatalogIndexEntry;
import com.sunshine.expert.exception.ExpertErrorCode;
import com.sunshine.expert.service.ExpertAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/experts")
@RequiredArgsConstructor
public class ExpertCatalogController {
    private final ExpertAdminService expertAdminService;

    @GetMapping("/catalog")
    public R<Void> catalogRemoved() {
        throw new BizException(CommonErrorCode.GONE);
    }

    @GetMapping("/catalog/index")
    public R<List<ExpertCatalogIndexEntry>> catalogIndex() {
        return R.ok(expertAdminService.listCatalogIndex());
    }

    @GetMapping("/{id}/catalog")
    public R<ExpertCatalogEntry> catalogDetail(@PathVariable String id) {
        return expertAdminService.findCatalogEntry(id)
                .map(R::ok)
                .orElseThrow(() -> new BizException(ExpertErrorCode.EXPERT_NOT_FOUND));
    }
}
