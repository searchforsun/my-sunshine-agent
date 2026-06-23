package com.sunshine.skill.storage;

import com.sunshine.skill.config.SkillStorageProperties;
import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.dto.SkillFileEntry;
import com.sunshine.skill.skillmd.SkillPackage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
public class SkillStorageService {

    private final SkillStorageProperties storageProperties;
    private final LocalSkillPackageStorage localStorage;
    private final ObjectProvider<MinioSkillPackageStorage> minioStorage;

    public SkillStorageService(
            SkillStorageProperties storageProperties,
            LocalSkillPackageStorage localStorage,
            ObjectProvider<MinioSkillPackageStorage> minioStorage) {
        this.storageProperties = storageProperties;
        this.localStorage = localStorage;
        this.minioStorage = minioStorage;
    }

    public String persistPackage(String skillId, int version, SkillPackage pkg) {
        try {
            if (storageProperties.isMinio()) {
                MinioSkillPackageStorage minio = requireMinio();
                return minio.save(skillId, version, pkg);
            }
            return localStorage.save(skillId, version, pkg);
        } catch (IOException e) {
            log.warn("[SkillStorage] 存储 skill 包失败 skill={}: {}", skillId, e.getMessage());
            return null;
        }
    }

    public String readSkillMd(String storageLocator) {
        if (SkillStorageLocator.isMinio(storageLocator)) {
            return requireMinio().readSkillMd(storageLocator);
        }
        return localStorage.readSkillMd(storageLocator);
    }

    public List<SkillFileEntry> listFiles(String skillId, int version, String storageLocator) {
        try {
            if (shouldUseMinio(storageLocator)) {
                return requireMinio().listFiles(skillId, version, storageLocator);
            }
            return localStorage.listFiles(skillId, version, storageLocator);
        } catch (IOException e) {
            log.warn("[SkillStorage] 列举文件失败 skill={} v{}: {}", skillId, version, e.getMessage());
            return List.of();
        }
    }

    public SkillFileContent readFile(String skillId, int version, String storageLocator, String relativePath) {
        try {
            if (shouldUseMinio(storageLocator)) {
                return requireMinio().readFile(storageLocator, skillId, version, relativePath);
            }
            return localStorage.readFile(storageLocator, skillId, version, relativePath);
        } catch (IOException e) {
            return null;
        }
    }

    public void writeTextFile(String skillId, int version, String storageLocator, String relativePath, String content)
            throws IOException {
        if (shouldUseMinio(storageLocator)) {
            requireMinio().writeTextFile(storageLocator, skillId, version, relativePath, content);
            return;
        }
        localStorage.writeTextFile(storageLocator, skillId, version, relativePath, content);
    }

    /** 复制整包到新版本目录，返回新版本的 storagePath（SKILL.md 定位符） */
    public String copyPackage(String skillId, int sourceVersion, String sourceLocator, int targetVersion) {
        try {
            if (shouldUseMinio(sourceLocator)) {
                return requireMinio().copyVersion(skillId, sourceVersion, sourceLocator, targetVersion);
            }
            return localStorage.copyVersion(skillId, sourceVersion, sourceLocator, targetVersion);
        } catch (IOException e) {
            log.warn("[SkillStorage] 复制 skill 包失败 skill={} v{} -> v{}: {}",
                    skillId, sourceVersion, targetVersion, e.getMessage());
            return null;
        }
    }

    public void deletePackage(String skillId, int version, String storageLocator) {
        try {
            if (shouldUseMinio(storageLocator)) {
                requireMinio().deleteVersion(skillId, version, storageLocator);
            } else {
                localStorage.deleteVersion(skillId, version, storageLocator);
            }
        } catch (IOException e) {
            log.warn("[SkillStorage] 删除 skill 包失败 skill={} v{}: {}", skillId, version, e.getMessage());
        }
    }

    private MinioSkillPackageStorage requireMinio() {
        MinioSkillPackageStorage minio = minioStorage.getIfAvailable();
        if (minio == null) {
            throw new IllegalStateException("MinIO 存储未启用，请设置 skill.storage.type=minio");
        }
        return minio;
    }

    private boolean shouldUseMinio(String storageLocator) {
        if (SkillStorageLocator.isMinio(storageLocator)) {
            return true;
        }
        return storageProperties.isMinio() && !StringUtils.hasText(storageLocator);
    }
}
