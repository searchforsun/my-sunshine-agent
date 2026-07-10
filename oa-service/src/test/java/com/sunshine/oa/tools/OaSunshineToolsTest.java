package com.sunshine.oa.tools;

import com.sunshine.oa.dto.OaTaskVO;
import com.sunshine.oa.service.OaTaskService;
import com.sunshine.tools.sdk.autoconfigure.SunshineToolAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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

    @MockBean
    private OaTaskService taskService;

    @SpringBootApplication
    @Import({SunshineToolAutoConfiguration.class, OaSunshineTools.class})
    static class TestApplication {
    }

    @Test
    void listOaTasks_formatsText() {
        when(taskService.list(eq("pending"))).thenReturn(List.of(
                new OaTaskVO(2001, "请假审批-张三年假", "leave", "pending", "部门经理", "2026-06-20")));
        String result = oaSunshineTools.listOaTasks("pending");
        assertThat(result).contains("共 1 条 OA 待办")
                .contains("[2001]")
                .contains("请假审批-张三年假")
                .contains("处理人=部门经理");
    }

    @Test
    void approveOaTask_returnsSimulatedWriteResult() {
        assertThat(oaSunshineTools.approveOaTask("2001"))
                .isEqualTo("已审批待办 2001（模拟写操作）");
    }

    @Test
    void catalogReturnsOaTools() throws Exception {
        mockMvc.perform(get("/sunshine/tools/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value("sunshine-oa"))
                .andExpect(jsonPath("$.tools[?(@.name=='approve_oa_task')].sideEffect").value("write"));
    }

    @Test
    void invokeListOaTasksReturnsResult() throws Exception {
        when(taskService.list(eq("pending"))).thenReturn(List.of(
                new OaTaskVO(2001, "请假审批-张三年假", "leave", "pending", "部门经理", "2026-06-20")));
        mockMvc.perform(post("/sunshine/tools/invoke/list_oa_tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("共 1 条 OA 待办")));
    }
}
