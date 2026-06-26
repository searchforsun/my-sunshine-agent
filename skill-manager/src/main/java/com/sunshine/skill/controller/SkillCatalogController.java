package com.sunshine.skill.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.skill.exception.SkillErrorCode;
import com.sunshine.skill.dto.SkillCatalogEntry;
import com.sunshine.skill.dto.SkillCatalogIndexEntry;
import com.sunshine.skill.service.SkillAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Runtime Catalog — 摘要与详情分离（动态披露） */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillCatalogController {

    private final SkillAdminService skillAdminService;

    /** 兼容旧路径：仅返回摘要（无 systemOverlay） */
    @GetMapping("/catalog")
    public R<List<SkillCatalogIndexEntry>> catalog() {
        return R.ok(skillAdminService.listCatalogIndex());
    }

    @GetMapping("/catalog/index")
    public R<List<SkillCatalogIndexEntry>> catalogIndex() {
        return R.ok(skillAdminService.listCatalogIndex());
    }

    @GetMapping("/{id}/catalog")
    public R<SkillCatalogEntry> catalogDetail(@PathVariable String id) {
        return skillAdminService.findCatalogEntry(id)
                .map(R::ok)
                .orElseThrow(() -> new BizException(SkillErrorCode.SKILL_NOT_ENABLED));
    }
}
