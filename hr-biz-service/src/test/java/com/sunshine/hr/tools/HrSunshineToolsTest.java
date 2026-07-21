package com.sunshine.hr.tools;

import com.sunshine.hr.service.HrBizService;
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

@SpringBootTest(classes = HrSunshineToolsTest.TestApplication.class,
        properties = "sunshine.tools.app-id=sunshine-hr")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/data-hr.sql")
class HrSunshineToolsTest {

    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HrSunshineTools hrSunshineTools;

    @Autowired
    private HrBizService hrBizService;

    @SpringBootApplication(scanBasePackages = "com.sunshine.hr")
    @Import(SunshineToolAutoConfiguration.class)
    @EntityScan("com.sunshine.hr.entity")
    @EnableJpaRepositories("com.sunshine.hr.repo")
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
    void getLeaveBalance_withAliceContext_returnsQingsong() {
        ToolInvocationContext.set("default", ALICE);
        String result = hrSunshineTools.getLeaveBalance("2026");
        assertThat(result).contains("青松假=12")
                .contains("年假=5")
                .contains("调休=3");
    }

    @Test
    void getLeaveBalance_withoutContext_throws() {
        ToolInvocationContext.clear();
        assertThatThrownBy(() -> hrSunshineTools.getLeaveBalance(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x-user-id");
    }

    @Test
    void invokeWithoutUserHeader_returnsFailure() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/get_leave_balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("x-user-id")));
    }

    @Test
    void invokeWithAliceHeader_returnsResult() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/get_leave_balance")
                        .header("x-user-id", ALICE)
                        .header("x-tenant-id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":\"2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("青松假=12")));
    }

    @Test
    void catalogReturnsHrTools() throws Exception {
        mockMvc.perform(get("/sunshine/tools/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value("sunshine-hr"))
                .andExpect(jsonPath("$.tools.length()").value(4))
                .andExpect(jsonPath("$.tools[?(@.name=='get_leave_balance')].displayName")
                        .value("查询假期余额"))
                .andExpect(jsonPath("$.tools[?(@.name=='list_leave_requests')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='submit_leave_request')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='get_attendance_month')]").exists());
    }

    @Test
    void listLeaveRequests_bobSeesEmpty_aliceSeesSeed() {
        ToolInvocationContext.set("default", BOB);
        assertThat(hrSunshineTools.listLeaveRequests(null)).contains("未查询到");
        ToolInvocationContext.set("default", ALICE);
        assertThat(hrSunshineTools.listLeaveRequests("pending"))
                .contains("共 1 条请假单")
                .contains("[leave-a2]")
                .contains("青松假");
    }

    @Test
    void submitLeaveRequest_createsForCurrentUser() {
        ToolInvocationContext.set("default", BOB);
        String result = hrSunshineTools.submitLeaveRequest(
                "annual", "2026-07-22", "2026-07-23", "事假");
        assertThat(result).contains("已提交请假单").contains("id=leave-");
        assertThat(hrBizService.listLeaveRequests("default", BOB, null)).hasSize(1);
        assertThat(hrBizService.listLeaveRequests("default", ALICE, null))
                .extracting(r -> r.id())
                .doesNotContain(hrBizService.listLeaveRequests("default", BOB, null).get(0).id());
    }

    @Test
    void getAttendanceMonth_aliceJuly() {
        ToolInvocationContext.set("default", ALICE);
        String result = hrSunshineTools.getAttendanceMonth("2026-07");
        assertThat(result).contains("迟到=2")
                .contains("加班=8.5")
                .contains("霜降");
    }
}
