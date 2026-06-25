package com.sunshine.skill.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.dto.SkillVersionDiffResponse;
import com.sunshine.skill.util.ContentMd5;
import com.sunshine.skill.util.LineDiff;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SkillVersionDiffService {

    private final SkillFileService skillFileService;

    public SkillVersionDiffResponse diff(String skillId, int fromVersion, int toVersion, String path) {
        if (fromVersion == toVersion) {
            throw new BizException("对比版本不能相同");
        }
        String relativePath = StringUtils.hasText(path) ? path.strip() : "SKILL.md";
        SkillFileContent from = skillFileService.readFile(skillId, fromVersion, relativePath);
        SkillFileContent to = skillFileService.readFile(skillId, toVersion, relativePath);
        if (from.binary() || to.binary()) {
            String fromMd5 = ContentMd5.hexFromBase64(from.content());
            String toMd5 = ContentMd5.hexFromBase64(to.content());
            return new SkillVersionDiffResponse(
                    relativePath,
                    fromVersion,
                    toVersion,
                    true,
                    fromMd5,
                    toMd5,
                    List.of());
        }
        return new SkillVersionDiffResponse(
                relativePath,
                fromVersion,
                toVersion,
                false,
                null,
                null,
                LineDiff.diff(from.content(), to.content()));
    }
}
