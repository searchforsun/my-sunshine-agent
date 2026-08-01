package com.sunshine.sandbox.api;

import com.sunshine.common.core.result.R;
import com.sunshine.common.sandbox.CreateSessionRequest;
import com.sunshine.common.sandbox.CreateSessionResponse;
import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.common.sandbox.FsNodeDto;
import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.sandbox.docker.SandboxInvocationRegistry;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final SandboxInvocationRegistry invocationRegistry;

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

    @PostMapping("/{id}/stop")
    public R<Void> stop(@PathVariable String id) {
        sessions.stop(id);
        return R.ok(null);
    }

    @PostMapping("/{id}/start")
    public R<Void> start(@PathVariable String id) {
        sessions.start(id);
        return R.ok(null);
    }

    @GetMapping("/{id}/alive")
    public R<Map<String, Boolean>> alive(@PathVariable String id) {
        boolean present = fs.alive(id);
        boolean running = present && sessions.isRunning(id);
        return R.ok(Map.of("alive", present, "running", running));
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
            @RequestParam(value = "maxChars", required = false, defaultValue = "200000") int maxChars,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {
        return R.ok(fs.readContent(id, path, maxChars, offset));
    }

    @PostMapping("/{id}/tools/{name}")
    public R<ToolInvokeResponse> invoke(
            @PathVariable String id,
            @PathVariable String name,
            @RequestHeader(value = "x-sandbox-invocation-id", required = false) String invocationId,
            @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(tools.invoke(id, name, body, invocationId));
    }

    /** 取消进行中的工具调用（docker exec Process 或 host glob/grep 协作标志） */
    @PostMapping("/{id}/invocations/{invocationId}/cancel")
    public R<Map<String, Boolean>> cancelInvocation(
            @PathVariable String id,
            @PathVariable String invocationId) {
        boolean cancelled = invocationRegistry.cancel(id, invocationId);
        return R.ok(Map.of("cancelled", cancelled));
    }
}
