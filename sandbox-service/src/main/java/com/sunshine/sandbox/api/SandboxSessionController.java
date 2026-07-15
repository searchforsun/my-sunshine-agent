package com.sunshine.sandbox.api;

import com.sunshine.common.core.result.R;
import com.sunshine.sandbox.session.SandboxSessionService;
import com.sunshine.sandbox.tool.SandboxToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sandbox/sessions")
@RequiredArgsConstructor
public class SandboxSessionController {

    private final SandboxSessionService sessions;
    private final SandboxToolExecutor tools;

    @PostMapping
    public R<CreateSessionResponse> create(@RequestBody CreateSessionRequest req) {
        return R.ok(new CreateSessionResponse(sessions.create(req)));
    }

    @DeleteMapping("/{id}")
    public R<Void> close(@PathVariable String id) {
        sessions.close(id);
        return R.ok(null);
    }

    @PostMapping("/{id}/tools/{name}")
    public R<ToolInvokeResponse> invoke(
            @PathVariable String id,
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(tools.invoke(id, name, body));
    }
}
