package com.sunshine.skill.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.skill.dto.SkillCatalogEntry;
import com.sunshine.skill.dto.SkillCreateRequest;
import com.sunshine.skill.dto.SkillEnableRequest;
import com.sunshine.skill.dto.SkillUpdateRequest;
import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.dto.SkillFileEntry;
import com.sunshine.skill.dto.SkillFileWriteRequest;
import com.sunshine.skill.entity.SkillVersionEntity;
import com.sunshine.skill.service.SkillAdminService;
import com.sunshine.skill.service.SkillFileService;
import com.sunshine.skill.dto.SkillVersionDiffResponse;
import com.sunshine.skill.service.SkillVersionDiffService;
import com.sunshine.skill.skillmd.SkillPackage;
import com.sunshine.skill.skillmd.SkillPackageImporter;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillAdminController {

    private final SkillAdminService skillAdminService;
    private final SkillFileService skillFileService;
    private final SkillVersionDiffService skillVersionDiffService;

    @GetMapping
    public R<List<SkillCatalogEntry>> list() {
        return R.ok(skillAdminService.listAll());
    }

    @PostMapping
    public R<SkillCatalogEntry> create(@RequestBody SkillCreateRequest request) {
        return R.ok(skillAdminService.create(request));
    }

    @PostMapping("/{id}/upload")
    public R<SkillCatalogEntry> upload(
            @PathVariable String id,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "content", required = false) String content) throws Exception {
        SkillPackage pkg = resolvePackage(file, content);
        return R.ok(skillAdminService.uploadPackage(id, pkg, userId));
    }

    @PutMapping("/{id}/enable")
    public R<SkillCatalogEntry> enable(@PathVariable String id, @RequestBody SkillEnableRequest request) {
        return R.ok(skillAdminService.setEnabled(id, request.enabled()));
    }

    @PutMapping("/{id}")
    public R<SkillCatalogEntry> update(@PathVariable String id, @RequestBody SkillUpdateRequest request) {
        return R.ok(skillAdminService.updateMeta(id, request));
    }

    @GetMapping("/{id}/versions")
    public R<List<SkillVersionEntity>> versions(@PathVariable String id) {
        return R.ok(skillAdminService.listVersions(id));
    }

    @PostMapping("/{id}/publish")
    public R<SkillCatalogEntry> publish(
            @PathVariable String id,
            @RequestParam int version,
            @RequestHeader(value = "x-user-id", required = false) String userId) {
        return R.ok(skillAdminService.publish(id, version, userId));
    }

    @PostMapping("/{id}/versions/{version}/fork")
    public R<SkillCatalogEntry> forkVersion(
            @PathVariable String id,
            @PathVariable int version,
            @RequestHeader(value = "x-user-id", required = false) String userId) {
        return R.ok(skillAdminService.forkVersion(id, version, userId));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        skillAdminService.delete(id);
        return R.ok(null);
    }

    @DeleteMapping("/{id}/versions/{version}")
    public R<SkillCatalogEntry> deleteVersion(@PathVariable String id, @PathVariable int version) {
        return R.ok(skillAdminService.deleteVersion(id, version));
    }

    @GetMapping("/{id}/versions/diff")
    public R<SkillVersionDiffResponse> diffVersions(
            @PathVariable String id,
            @RequestParam int from,
            @RequestParam int to,
            @RequestParam(required = false) String path) {
        return R.ok(skillVersionDiffService.diff(id, from, to, path));
    }

    @GetMapping("/{id}/versions/{version}/files")
    public R<List<SkillFileEntry>> listFiles(@PathVariable String id, @PathVariable int version) {
        return R.ok(skillFileService.listFiles(id, version));
    }

    @GetMapping("/{id}/versions/{version}/file")
    public R<SkillFileContent> readFile(
            @PathVariable String id,
            @PathVariable int version,
            @RequestParam String path) {
        return R.ok(skillFileService.readFile(id, version, path));
    }

    @PutMapping("/{id}/versions/{version}/file")
    public R<SkillFileContent> writeFile(
            @PathVariable String id,
            @PathVariable int version,
            @RequestParam String path,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestBody SkillFileWriteRequest request) {
        return R.ok(skillFileService.writeFile(id, version, path, request.content(), userId));
    }

    @GetMapping("/{id}/versions/{version}/download")
    public ResponseEntity<byte[]> downloadPackage(@PathVariable String id, @PathVariable int version) {
        byte[] zip = skillFileService.exportPackageZip(id, version);
        String filename = skillFileService.downloadFilename(id, version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    private static SkillPackage resolvePackage(MultipartFile file, String content) throws Exception {
        if (file != null && !file.isEmpty()) {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
            byte[] bytes = file.getBytes();
            if (filename.endsWith(".zip")) {
                return SkillPackageImporter.fromZip(bytes);
            }
            return SkillPackageImporter.fromMarkdown(new String(bytes, StandardCharsets.UTF_8));
        }
        if (StringUtils.hasText(content)) {
            return SkillPackageImporter.fromMarkdown(content);
        }
        throw new ResponseStatusException(BAD_REQUEST, "请上传 SKILL.md / zip 或提供 content");
    }
}
