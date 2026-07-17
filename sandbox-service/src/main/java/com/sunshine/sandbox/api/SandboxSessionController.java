package com.sunshine.sandbox.api;

import com.sunshine.common.core.result.R;
import com.sunshine.sandbox.fs.SandboxFsService;
import com.sunshine.sandbox.session.SandboxSessionService;
import com.sunshine.sandbox.tool.SandboxToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sandbox/sessions")
@RequiredArgsConstructor
public class SandboxSessionController {

    private final SandboxSessionService sessions;
    private final SandboxToolExecutor tools;
    private final SandboxFsService fs;

    @PostMapping
    public R<CreateSessionResponse> create(@RequestBody CreateSessionRequest req) {
        return R.ok(new CreateSessionResponse(sessions.create(req)));
    }

    @PutMapping("/{id}/skills/{skillId}")
    public R<Void> mountSkill(
            @PathVariable String id,
            @PathVariable String skillId,
            @RequestBody(required = false) Map<String, String> skillFiles) {
        sessions.mountSkill(id, skillId, skillFiles != null ? skillFiles : Map.of());
        return R.ok(null);
    }

    @DeleteMapping("/{id}")
    public R<Void> close(@PathVariable String id) {
        sessions.close(id);
        return R.ok(null);
    }

    @GetMapping("/{id}/alive")
    public R<Map<String, Boolean>> alive(@PathVariable String id) {
        return R.ok(Map.of("alive", fs.alive(id)));
    }

    @GetMapping("/{id}/fs")
    public R<FsNodeDto.FsListResponse> listFs(
            @PathVariable String id,
            @RequestParam(value = "path", required = false, defaultValue = "/workspace") String path) {
        return R.ok(fs.list(id, path));
    }

    @GetMapping("/{id}/fs/content")
    public R<FsContentDto> readFs(
            @PathVariable String id,
            @RequestParam("path") String path,
            @RequestParam(value = "maxChars", required = false, defaultValue = "200000") int maxChars) {
        return R.ok(fs.readContent(id, path, maxChars));
    }

    @PostMapping("/{id}/tools/{name}")
    public R<ToolInvokeResponse> invoke(
            @PathVariable String id,
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(tools.invoke(id, name, body));
    }
}
