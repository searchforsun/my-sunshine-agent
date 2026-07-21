package com.sunshine.finance;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.finance.controller.FinanceController;
import com.sunshine.finance.store.TenantUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FinanceController.class)
@Import({TenantUserStore.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class FinanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantUserStore store;

    @BeforeEach
    void reset() {
        store.reset("default");
    }

    @Test
    void listExpenses_requiresUserHeader() throws Exception {
        mockMvc.perform(get("/api/finance/expenses"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listExpenses_aliceSeesSeed() throws Exception {
        mockMvc.perform(get("/api/finance/expenses").header("x-user-id", "u-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("exp-a1"));
    }

    @Test
    void listExpenses_bobSeesEmpty() throws Exception {
        mockMvc.perform(get("/api/finance/expenses").header("x-user-id", "u-bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getExpense_crossUser_notFound() throws Exception {
        mockMvc.perform(get("/api/finance/expenses/exp-a1").header("x-user-id", "u-bob"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void submitExpense_addsForUser() throws Exception {
        mockMvc.perform(post("/api/finance/expenses")
                        .header("x-user-id", "u-bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"办公用品","amount":42.00,"occurredOn":"2026-07-20","remark":"打印纸"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(org.hamcrest.Matchers.startsWith("exp-")))
                .andExpect(jsonPath("$.data.status").value("pending"));
        mockMvc.perform(get("/api/finance/expenses").header("x-user-id", "u-bob"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void summarizeExpenses_returnsRows() throws Exception {
        mockMvc.perform(get("/api/finance/expenses/summary").header("x-user-id", "u-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data[0].status").exists());
    }

    @Test
    void listInbox_carolSeesItem() throws Exception {
        mockMvc.perform(get("/api/finance/inbox").header("x-user-id", "u-carol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("inbox-c1"));
    }

    @Test
    void getInboxItem_returnsDetail() throws Exception {
        mockMvc.perform(get("/api/finance/inbox/inbox-a1").header("x-user-id", "u-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("报销单待补充发票"));
    }
}
