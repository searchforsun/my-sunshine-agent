package com.sunshine.bff.controller;

import com.sunshine.bff.client.SkillManagerClient;
import com.sunshine.bff.client.ToolManagerClient;
import com.sunshine.bff.service.SkillMaintainerEnricher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 skill-manager / tool-manager Catalog */
@RestController
@RequiredArgsConstructor
public class SkillsController {

    private final SkillManagerClient skillManagerClient;
    private final ToolManagerClient toolManagerClient;
    private final SkillMaintainerEnricher maintainerEnricher;

    @GetMapping("/api/skills")
    public Mono<Map<String, Object>> listSkills() {
        return skillManagerClient.listSkills().flatMap(maintainerEnricher::enrich);
    }

    @PostMapping("/api/skills")
    public Mono<Map<String, Object>> createSkill(@RequestBody Map<String, Object> body) {
        return skillManagerClient.createSkill(body).flatMap(maintainerEnricher::enrich);
    }

    @PostMapping(value = "/api/skills/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadSkill(
            @PathVariable String id,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestPart(value = "file", required = false) FilePart file,
            @RequestPart(value = "content", required = false) String content) {
        return skillManagerClient.upload(id, file, content, userId).flatMap(maintainerEnricher::enrich);
    }

    @PutMapping("/api/skills/{id}/enable")
    public Mono<Map<String, Object>> enableSkill(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return skillManagerClient.setEnabled(id, enabled).flatMap(maintainerEnricher::enrich);
    }

    @PutMapping("/api/skills/{id}")
    public Mono<Map<String, Object>> updateSkill(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return skillManagerClient.updateSkill(id, body).flatMap(maintainerEnricher::enrich);
    }

    @GetMapping("/api/skills/{id}/versions")
    public Mono<Map<String, Object>> listVersions(@PathVariable String id) {
        return skillManagerClient.listVersions(id).flatMap(maintainerEnricher::enrich);
    }

    @GetMapping("/api/skills/{id}/versions/diff")
    public Mono<Map<String, Object>> diffVersions(
            @PathVariable String id,
            @RequestParam int from,
            @RequestParam int to,
            @RequestParam(required = false) String path) {
        return skillManagerClient.diffVersions(id, from, to, path);
    }

    @PostMapping("/api/skills/{id}/publish")
    public Mono<Map<String, Object>> publish(
            @PathVariable String id,
            @RequestParam int version,
            @RequestHeader(value = "x-user-id", required = false) String userId) {
        return skillManagerClient.publish(id, version, userId).flatMap(maintainerEnricher::enrich);
    }

    @PostMapping("/api/skills/{id}/versions/{version}/fork")
    public Mono<Map<String, Object>> forkVersion(
            @PathVariable String id,
            @PathVariable int version,
            @RequestHeader(value = "x-user-id", required = false) String userId) {
        return skillManagerClient.forkVersion(id, version, userId).flatMap(maintainerEnricher::enrich);
    }

    @DeleteMapping("/api/skills/{id}")
    public Mono<Map<String, Object>> deleteSkill(@PathVariable String id) {
        return skillManagerClient.delete(id);
    }

    @DeleteMapping("/api/skills/{id}/versions/{version}")
    public Mono<Map<String, Object>> deleteSkillVersion(
            @PathVariable String id,
            @PathVariable int version) {
        return skillManagerClient.deleteVersion(id, version).flatMap(maintainerEnricher::enrich);
    }

    @GetMapping("/api/skills/{id}/versions/{version}/files")
    public Mono<Map<String, Object>> listFiles(
            @PathVariable String id,
            @PathVariable int version) {
        return skillManagerClient.listFiles(id, version);
    }

    @GetMapping("/api/skills/{id}/versions/{version}/file")
    public Mono<Map<String, Object>> readFile(
            @PathVariable String id,
            @PathVariable int version,
            @RequestParam String path) {
        return skillManagerClient.readFile(id, version, path);
    }

    @PutMapping("/api/skills/{id}/versions/{version}/file")
    public Mono<Map<String, Object>> writeFile(
            @PathVariable String id,
            @PathVariable int version,
            @RequestParam String path,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestBody Map<String, Object> body) {
        Object content = body.get("content");
        if (content == null) {
            return Mono.error(new IllegalArgumentException("content 必填"));
        }
        return skillManagerClient.writeFile(id, version, path, String.valueOf(content), userId);
    }

    @GetMapping("/api/skills/{id}/versions/{version}/download")
    public Mono<ResponseEntity<byte[]>> downloadPackage(
            @PathVariable String id,
            @PathVariable int version) {
        return skillManagerClient.downloadPackage(id, version);
    }

    @GetMapping("/api/tools/catalog")
    public Mono<Map<String, Object>> toolCatalog() {
        return toolManagerClient.catalog();
    }

    @GetMapping("/api/skills/catalog/index")
    public Mono<Map<String, Object>> skillCatalogIndex() {
        return skillManagerClient.catalogIndex();
    }

    @GetMapping("/api/skills/{id}/catalog")
    public Mono<Map<String, Object>> skillCatalogDetail(@PathVariable String id) {
        return skillManagerClient.catalogDetail(id);
    }
}
