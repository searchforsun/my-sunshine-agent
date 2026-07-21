package com.sunshine.hr;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.hr.controller.BizHrController;
import com.sunshine.hr.service.HrBizService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureDataJpa;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BizHrController.class)
@AutoConfigureDataJpa
@Import({HrBizService.class, GlobalExceptionHandler.class})
@EntityScan("com.sunshine.hr.entity")
@EnableJpaRepositories("com.sunshine.hr.repo")
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/data-hr.sql")
class BizHrControllerTest {

    static final String TOKEN = "sunshine-mock-admin-dev";
    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listLeaveBalances_requiresAdminToken() throws Exception {
        mockMvc.perform(get("/api/biz/hr/leave-balances"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listLeaveBalances_withToken_nonEmpty() throws Exception {
        mockMvc.perform(get("/api/biz/hr/leave-balances").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.data[?(@.userId=='" + ALICE + "')].qingsong").value(12.0));
    }

    @Test
    void createUpdateDelete_leaveBalance_compositeKey() throws Exception {
        String body = """
                {"userId":"%s","year":2027,"annual":3,"qingsong":1,"compensatory":0}
                """.formatted(BOB);
        mockMvc.perform(post("/api/biz/hr/leave-balances")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(BOB))
                .andExpect(jsonPath("$.data.year").value(2027))
                .andExpect(jsonPath("$.data.qingsong").value(1));

        mockMvc.perform(put("/api/biz/hr/leave-balances/" + BOB + "/2027")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"annual":4,"qingsong":2,"compensatory":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.annual").value(4))
                .andExpect(jsonPath("$.data.qingsong").value(2));

        mockMvc.perform(delete("/api/biz/hr/leave-balances/" + BOB + "/2027")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("deleted"));
    }

    @Test
    void createUpdateDelete_leaveRequest() throws Exception {
        String body = """
                {"userId":"%s","leaveType":"annual","startDate":"2026-09-01","endDate":"2026-09-02","reason":"旅行","status":"pending"}
                """.formatted(ALICE);
        String raw = mockMvc.perform(post("/api/biz/hr/leave-requests")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(startsWith("leave-")))
                .andExpect(jsonPath("$.data.userId").value(ALICE))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String leaveId = raw.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(put("/api/biz/hr/leave-requests/" + leaveId)
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","leaveType":"annual","startDate":"2026-09-01","endDate":"2026-09-03","reason":"延长","status":"approved"}
                                """.formatted(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"))
                .andExpect(jsonPath("$.data.endDate").value("2026-09-03"));

        mockMvc.perform(delete("/api/biz/hr/leave-requests/" + leaveId)
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void createUpdateDelete_attendanceMonth_compositeKey() throws Exception {
        String body = """
                {"userId":"%s","yearMonth":"2026-08","lateCount":1,"overtimeHours":2.5,"frostLedgerSummary":"八月摘要"}
                """.formatted(ALICE);
        mockMvc.perform(post("/api/biz/hr/attendance-months")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.yearMonth").value("2026-08"))
                .andExpect(jsonPath("$.data.lateCount").value(1));

        mockMvc.perform(put("/api/biz/hr/attendance-months/" + ALICE + "/2026-08")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lateCount":3,"overtimeHours":5.0,"frostLedgerSummary":"更新"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lateCount").value(3));

        mockMvc.perform(delete("/api/biz/hr/attendance-months/" + ALICE + "/2026-08")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("deleted"));
    }
}
