package com.sunshine.finance.tools;

import com.sunshine.finance.dto.FinanceMessageSummaryVO;
import com.sunshine.finance.dto.FinanceMessageVO;
import com.sunshine.finance.service.FinanceMessageService;
import com.sunshine.tools.sdk.autoconfigure.SunshineToolAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FinanceSunshineToolsTest.TestApplication.class,
        properties = "sunshine.tools.app-id=sunshine-finance")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinanceSunshineToolsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FinanceSunshineTools financeSunshineTools;

    @MockBean
    private FinanceMessageService messageService;

    @SpringBootApplication
    @Import({SunshineToolAutoConfiguration.class, FinanceSunshineTools.class})
    static class TestApplication {
    }

    @Test
    void listFinanceMessages_formatsText() {
        when(messageService.list(eq("pending"))).thenReturn(List.of(
                new FinanceMessageVO(1001, "Q2 差旅报销审批", "reimbursement", "pending",
                        new BigDecimal("3280.50"), "张三", "2026-06-14 09:30")));
        String result = financeSunshineTools.listFinanceMessages("pending");
        assertThat(result).contains("共 1 条财务消息")
                .contains("[1001]")
                .contains("Q2 差旅报销审批")
                .contains("申请人=张三");
    }

    @Test
    void getFinanceMessageDetail_formatsText() {
        when(messageService.getById(1001L)).thenReturn(java.util.Optional.of(
                new FinanceMessageVO(1001, "Q2 差旅报销审批", "reimbursement", "pending",
                        new BigDecimal("3280.50"), "张三", "2026-06-14 09:30")));
        String result = financeSunshineTools.getFinanceMessageDetail("1001");
        assertThat(result).contains("财务消息详情")
                .contains("id=1001")
                .contains("标题=Q2 差旅报销审批");
    }

    @Test
    void summarizeFinanceByStatus_formatsText() {
        when(messageService.summarize(eq("pending"))).thenReturn(List.of(
                new FinanceMessageSummaryVO("pending", 3, new BigDecimal("123456.50"))));
        String result = financeSunshineTools.summarizeFinanceByStatus("pending");
        assertThat(result).contains("财务消息汇总")
                .contains("status=pending")
                .contains("count=3");
    }

    @Test
    void catalogReturnsFinanceTools() throws Exception {
        mockMvc.perform(get("/sunshine/tools/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value("sunshine-finance"))
                .andExpect(jsonPath("$.tools[?(@.name=='list_finance_messages')].displayName")
                        .value("查询待审批财务消息"));
    }

    @Test
    void invokeListFinanceMessagesReturnsResult() throws Exception {
        when(messageService.list(eq("pending"))).thenReturn(List.of(
                new FinanceMessageVO(1001, "Q2 差旅报销审批", "reimbursement", "pending",
                        new BigDecimal("3280.50"), "张三", "2026-06-14 09:30")));
        mockMvc.perform(post("/sunshine/tools/invoke/list_finance_messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("共 1 条财务消息")));
    }
}
