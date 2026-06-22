package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartNodeHandlerTest {

    @AfterEach
    void tearDown() {
        QueryRewriteTrace.clear("msg-1");
    }

    @Test
    void usesIntentRewrittenQueryWhenApplied() {
        QueryRewriteTrace.bind("msg-1");
        QueryRewriteTrace.record("msg-1",
                QueryRewriteOutcome.of("intent", "我打车了", "请问如何报销打车费用？", 50L));

        assertThat(StartNodeHandler.resolveEffectiveUserQuery("我打车了", "msg-1"))
                .isEqualTo("请问如何报销打车费用？");
    }

    @Test
    void keepsOriginalWhenIntentRewriteSkipped() {
        assertThat(StartNodeHandler.resolveEffectiveUserQuery("请问年假可以请几天", "msg-2"))
                .isEqualTo("请问年假可以请几天");
    }
}
