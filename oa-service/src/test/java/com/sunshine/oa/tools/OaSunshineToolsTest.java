package com.sunshine.oa.tools;

import com.sunshine.oa.service.OaBizService;
import com.sunshine.tools.sdk.autoconfigure.SunshineToolAutoConfiguration;
import com.sunshine.tools.sdk.context.ToolInvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OaSunshineToolsTest.TestApplication.class,
        properties = "sunshine.tools.app-id=sunshine-oa")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/data-oa.sql")
class OaSunshineToolsTest {

    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OaSunshineTools oaSunshineTools;

    @Autowired
    private OaBizService oaBizService;

    @SpringBootApplication(scanBasePackages = "com.sunshine.oa")
    @Import(SunshineToolAutoConfiguration.class)
    @EntityScan("com.sunshine.oa.entity")
    @EnableJpaRepositories("com.sunshine.oa.repo")
    static class TestApplication {
    }

    @BeforeEach
    void setUp() {
        ToolInvocationContext.clear();
    }

    @AfterEach
    void tearDown() {
        ToolInvocationContext.clear();
    }

    @Test
    void listOaTasks_aliceDoesNotSeeBobsTasks() {
        ToolInvocationContext.set("default", ALICE);
        String aliceResult = oaSunshineTools.listOaTasks("pending");
        assertThat(aliceResult).contains("task-a1").doesNotContain("task-b1", "task-b2");

        ToolInvocationContext.set("default", BOB);
        String bobResult = oaSunshineTools.listOaTasks("pending");
        assertThat(bobResult).contains("共 2 条 OA 待办")
                .contains("task-b1")
                .contains("task-b2")
                .doesNotContain("task-a1");
    }

    @Test
    void approveOaTask_bobCanApproveOwn() {
        ToolInvocationContext.set("default", BOB);
        String result = oaSunshineTools.approveOaTask("task-b1");
        assertThat(result).contains("已审批待办 task-b1").contains("状态=done");
        assertThat(oaBizService.findTask("default", BOB, "task-b1").orElseThrow().status()).isEqualTo("done");
    }

    @Test
    void approveOaTask_aliceCannotApproveBobs() {
        ToolInvocationContext.set("default", ALICE);
        assertThat(oaSunshineTools.approveOaTask("task-b1"))
                .contains("无权审批或不存在");
        assertThat(oaBizService.findTask("default", BOB, "task-b1").orElseThrow().status()).isEqualTo("pending");
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
                        .header("x-user-id", BOB)
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
