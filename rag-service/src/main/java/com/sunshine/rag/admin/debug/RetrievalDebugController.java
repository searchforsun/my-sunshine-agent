package com.sunshine.rag.admin.debug;

import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/rag/admin/search")
@RequiredArgsConstructor
public class RetrievalDebugController {

    private final RetrievalDebugService retrievalDebugService;

    @PostMapping("/debug")
    public Mono<R<Map<String, Object>>> debugSearch(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> body) {
        return retrievalDebugService.debugSearch(tenantId, body)
                .map(result -> R.ok(result.toResponseMap()));
    }
}
