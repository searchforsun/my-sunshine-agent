package com.sunshine.skill.service;

import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.dto.SkillFileEntry;
import com.sunshine.skill.entity.SkillDefinitionEntity;
import com.sunshine.skill.entity.SkillVersionEntity;
import com.sunshine.skill.repo.SkillDefinitionRepository;
import com.sunshine.skill.repo.SkillVersionRepository;
import com.sunshine.skill.service.SkillCatalogRegistry;
import com.sunshine.skill.skillmd.SkillMdDocument;
import com.sunshine.skill.skillmd.SkillMdParser;
import com.sunshine.skill.storage.SkillStorageService;
import com.sunshine.skill.storage.SkillFileCodec;
import com.sunshine.skill.storage.SkillPackageExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class SkillFileService {

    private static final DateTimeFormatter DOWNLOAD_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    private final SkillStorageService skillStorageService;
    private final SkillDefinitionRepository definitionRepository;
    private final SkillVersionRepository versionRepository;
    private final SkillCatalogRegistry catalogRegistry;

    public List<SkillFileEntry> listFiles(String skillId, int version) {
        SkillVersionEntity ver = requireVersion(skillId, version);
        return skillStorageService.listFiles(skillId, version, ver.getStoragePath());
    }

    public SkillFileContent readFile(String skillId, int version, String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new ResponseStatusException(BAD_REQUEST, "path 必填");
        }
        SkillVersionEntity ver = requireVersion(skillId, version);
        SkillFileContent content = skillStorageService.readFile(
                skillId, version, ver.getStoragePath(), relativePath);
        if (content == null) {
            throw new ResponseStatusException(NOT_FOUND, "文件不存在: " + relativePath);
        }
        return content;
    }

    /** 在线编辑 — 仅草稿版本、文本文件；SKILL.md 会同步 overlay 与 definition.description */
    @Transactional
    public SkillFileContent writeFile(String skillId, int version, String relativePath, String content, String maintainer) {
        if (!StringUtils.hasText(relativePath)) {
            throw new ResponseStatusException(BAD_REQUEST, "path 必填");
        }
        if (content == null) {
            throw new ResponseStatusException(BAD_REQUEST, "content 必填");
        }
        SkillDefinitionEntity def = definitionRepository.findById(skillId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "skill 不存在: " + skillId));
        SkillVersionEntity ver = requireVersion(skillId, version);
        if (!"draft".equals(ver.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "仅草稿版本可在线编辑，请上传新版本后再改");
        }
        if (!StringUtils.hasText(ver.getStoragePath())) {
            throw new ResponseStatusException(BAD_REQUEST, "该版本无 Skill 包，请先上传");
        }
        String path = relativePath.strip().replace('\\', '/');
        String fileName = SkillFileCodec.lowerName(path);
        if (!SkillFileCodec.isTextFile(fileName)) {
            throw new ResponseStatusException(BAD_REQUEST, "不支持编辑二进制文件");
        }
        try {
            skillStorageService.writeTextFile(skillId, version, ver.getStoragePath(), path, content);
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "保存失败: " + e.getMessage());
        }
        if (isSkillMdPath(path)) {
            applySkillMdSideEffects(def, ver, content, skillId);
        }
        if (StringUtils.hasText(maintainer)) {
            ver.setMaintainer(maintainer.strip());
        }
        ver.setCreatedAt(java.time.Instant.now());
        versionRepository.save(ver);
        definitionRepository.save(def);
        catalogRegistry.refresh();
        SkillFileContent saved = skillStorageService.readFile(skillId, version, ver.getStoragePath(), path);
        if (saved == null) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "保存后读取失败");
        }
        return saved;
    }

    private void applySkillMdSideEffects(
            SkillDefinitionEntity def, SkillVersionEntity ver, String raw, String skillId) {
        SkillMdDocument doc;
        try {
            doc = SkillMdParser.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
        validateSkillName(skillId, doc.name());
        if (StringUtils.hasText(doc.description())) {
            def.setDescription(doc.description().strip());
        }
        ver.setSystemOverlay(doc.body());
        def.setUpdatedAt(java.time.Instant.now());
    }

    private static boolean isSkillMdPath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return "skill.md".equals(normalized) || normalized.endsWith("/skill.md");
    }

    private static void validateSkillName(String skillId, String name) {
        if (StringUtils.hasText(name) && !skillId.equals(name.strip())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "SKILL.md frontmatter name 与 skill id 不一致: " + name);
        }
    }

    public byte[] exportPackageZip(String skillId, int version) {
        SkillVersionEntity ver = requireVersion(skillId, version);
        if (!StringUtils.hasText(ver.getStoragePath())) {
            throw new ResponseStatusException(BAD_REQUEST, "该版本无 Skill 包，无法下载");
        }
        List<SkillFileEntry> files = skillStorageService.listFiles(skillId, version, ver.getStoragePath());
        boolean hasFile = files.stream().anyMatch(f -> !f.directory());
        if (!hasFile) {
            throw new ResponseStatusException(BAD_REQUEST, "该版本无文件，无法下载");
        }
        String storagePath = ver.getStoragePath();
        try {
            return SkillPackageExporter.toZip(files, path -> {
                SkillFileContent content = skillStorageService.readFile(skillId, version, storagePath, path);
                if (content == null) {
                    throw new UncheckedIOException(new IOException("文件不存在: " + path));
                }
                if (content.binary()) {
                    return Base64.getDecoder().decode(content.content());
                }
                return content.content().getBytes(StandardCharsets.UTF_8);
            });
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "打包下载失败: " + e.getMessage());
        } catch (UncheckedIOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "打包下载失败: " + e.getCause().getMessage());
        }
    }

    public String downloadFilename(String skillId, int version) {
        SkillVersionEntity ver = requireVersion(skillId, version);
        return skillId + "-" + formatVersionTimeForFilename(ver.getCreatedAt()) + ".zip";
    }

    private static String formatVersionTimeForFilename(Instant createdAt) {
        if (createdAt == null) {
            return "unknown";
        }
        return DOWNLOAD_TIME_FORMAT.format(createdAt);
    }

    private SkillVersionEntity requireVersion(String skillId, int version) {
        definitionRepository.findById(skillId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "skill 不存在: " + skillId));
        return versionRepository.findBySkillIdAndVersion(skillId, version)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "版本不存在"));
    }
}
