package com.sunshine.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = com.sunshine.tool.controller.ToolInvokeController.class)
@Import({com.sunshine.tool.service.ToolInvokeService.class,
        com.sunshine.tool.registry.ToolRegistry.class,
        com.sunshine.tool.tool.FinanceToolHandler.class})
@ActiveProfiles("test")
class ToolInvokeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private com.sunshine.tool.tool.FinanceTool financeTool;

    @Test
    void invokeFinanceTool() throws Exception {
        when(financeTool.listFinanceMessages(eq("pending"))).thenReturn("2 条待审批");

        mockMvc.perform(post("/api/tools/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"list_finance_messages\",\"params\":{\"status\":\"pending\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("2 条待审批"));
    }
}
