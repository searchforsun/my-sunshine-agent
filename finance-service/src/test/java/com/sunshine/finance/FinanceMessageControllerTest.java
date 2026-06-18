package com.sunshine.finance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.finance.controller.FinanceMessageController;
import com.sunshine.finance.service.FinanceMessageService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FinanceMessageController.class)
@Import({FinanceMessageService.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class FinanceMessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listMessages_returnsMockData() throws Exception {
        mockMvc.perform(get("/api/finance/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(5));
    }

    @Test
    void listMessages_filtersByPendingStatus() throws Exception {
        mockMvc.perform(get("/api/finance/messages").param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].status").value("pending"));
    }

    @Test
    void getMessage_returnsDetail() throws Exception {
        mockMvc.perform(get("/api/finance/messages/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.title").value("Q2 差旅报销审批"));
    }

    @Test
    void summarizeMessages_returnsPendingAndApproved() throws Exception {
        mockMvc.perform(get("/api/finance/messages/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").exists());
    }
}
