package com.sunshine.oa.tools;

import com.sunshine.oa.store.OaTenantUserStore;
import com.sunshine.tools.sdk.autoconfigure.SunshineToolAutoConfiguration;
import com.sunshine.tools.sdk.context.ToolInvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OaSunshineToolsTest.TestApplication.class,
        properties = "sunshine.tools.app-id=sunshine-oa")
@AutoConfigureMockMvc
class OaSunshineToolsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OaSunshineTools oaSunshineTools;

    @Autowired
    private OaTenantUserStore store;

    @SpringBootApplication
    @Import({SunshineToolAutoConfiguration.class, OaSunshineTools.class, OaTenantUserStore.class})
    static class TestApplication {
    }

    @BeforeEach
    void setUp() {
        store.reset("default");
        ToolInvocationContext.clear();
    }

    @AfterEach
    void tearDown() {
        ToolInvocationContext.clear();
    }

    @Test
    void listOaTasks_aliceDoesNotSeeBobsTasks() {
        ToolInvocationContext.set("default", "u-alice");
        String aliceResult = oaSunshineTools.listOaTasks("pending");
        assertThat(aliceResult).contains("task-a1").doesNotContain("task-b1", "task-b2");

        ToolInvocationContext.set("default", "u-bob");
        String bobResult = oaSunshineTools.listOaTasks("pending");
        assertThat(bobResult).contains("共 2 条 OA 待办")
                .contains("task-b1")
                .contains("task-b2")
                .doesNotContain("task-a1");
    }

    @Test
    void approveOaTask_bobCanApproveOwn() {
        ToolInvocationContext.set("default", "u-bob");
        String result = oaSunshineTools.approveOaTask("task-b1");
        assertThat(result).contains("已审批待办 task-b1").contains("状态=done");
        assertThat(store.findTask("default", "u-bob", "task-b1").orElseThrow().status()).isEqualTo("done");
    }

    @Test
    void approveOaTask_aliceCannotApproveBobs() {
        ToolInvocationContext.set("default", "u-alice");
        assertThat(oaSunshineTools.approveOaTask("task-b1"))
                .contains("无权审批或不存在");
        assertThat(store.findTask("default", "u-bob", "task-b1").orElseThrow().status()).isEqualTo("pending");
    }

    @Test
    void listOaTasks_withoutContext_throws() {
        ToolInvocationContext.clear();
        assertThatThrownBy(() -> oaSunshineTools.listOaTasks(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x-user-id");
    }

    @Test
    void catalogReturnsOaTools() throws Exception {
        mockMvc.perform(get("/sunshine/tools/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value("sunshine-oa"))
                .andExpect(jsonPath("$.tools[?(@.name=='list_oa_tasks')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='approve_oa_task')].sideEffect").value("write"));
    }

    @Test
    void invokeWithBobHeader_returnsResult() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/list_oa_tasks")
                        .header("x-user-id", "u-bob")
                        .header("x-tenant-id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("共 2 条 OA 待办")));
    }

    @Test
    void invokeWithoutUserHeader_returnsFailure() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/list_oa_tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("x-user-id")));
    }
}
