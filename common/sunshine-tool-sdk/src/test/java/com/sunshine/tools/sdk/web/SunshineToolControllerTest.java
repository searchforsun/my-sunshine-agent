package com.sunshine.tools.sdk.web;

import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import com.sunshine.tools.sdk.autoconfigure.SunshineToolAutoConfiguration;
import com.sunshine.tools.sdk.context.ToolInvocationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SunshineToolControllerTest.TestApplication.class,
        properties = "spring.application.name=test-app")
@AutoConfigureMockMvc
class SunshineToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @SpringBootApplication
    @Import(SunshineToolAutoConfiguration.class)
    static class TestApplication {
        @Bean
        SampleTools sampleTools() {
            return new SampleTools();
        }
    }

    static class SampleTools {
        @SunshineTool(
                id = "list_my_expenses",
                displayName = "查询待审批财务消息",
                description = "按状态筛选",
                timelineSummaryTemplate = "{count} 条财务消息")
        public String list(@ToolParam(value = "status", description = "pending|approved|all") String status) {
            return "ok-" + status;
        }

        @SunshineTool(
                id = "whoami",
                displayName = "当前调用身份",
                description = "读取 ToolInvocationContext")
        public String whoami() {
            return ToolInvocationContext.tenantIdOrDefault() + ":" + ToolInvocationContext.requireUserId();
        }
    }

    @Test
    void catalogReturnsTools() throws Exception {
        mockMvc.perform(get("/sunshine/tools/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value("test-app"))
                .andExpect(jsonPath("$.tools[0].name").value("list_my_expenses"))
                .andExpect(jsonPath("$.tools[0].displayName").value("查询待审批财务消息"));
    }

    @Test
    void invokeReturnsResult() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/list_my_expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value("ok-pending"));
    }

    @Test
    void invokeInjectsIdentityHeadersAndClearsAfter() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/whoami")
                        .header("x-user-id", "u42")
                        .header("x-tenant-id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value("acme:u42"));
        assertThatThrownBy(ToolInvocationContext::requireUserId)
                .isInstanceOf(IllegalStateException.class);
    }
}
