package com.sunshine.finance;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.finance.controller.MockAdminController;
import com.sunshine.finance.store.TenantUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MockAdminController.class)
@Import({TenantUserStore.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class MockAdminControllerTest {

    private static final String TOKEN = "sunshine-mock-admin-dev";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantUserStore store;

    @BeforeEach
    void reset() {
        store.reset("default");
    }

    @Test
    void users_missingToken_401() throws Exception {
        mockMvc.perform(get("/api/mock/finance/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void users_wrongToken_401() throws Exception {
        mockMvc.perform(get("/api/mock/finance/users").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void users_listsSeedUsers() throws Exception {
        mockMvc.perform(get("/api/mock/finance/users").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0]").value("u-alice"));
    }

    @Test
    void snapshot_returnsAliceExpenses() throws Exception {
        mockMvc.perform(get("/api/mock/finance/snapshot")
                        .param("userId", "u-alice")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenses.length()").value(2))
                .andExpect(jsonPath("$.data.expenses[0].id").value("exp-a1"));
    }

    @Test
    void reset_reloadsAfterMutation() throws Exception {
        store.submitExpense("default", "u-bob", "办公", new BigDecimal("10.00"), "2026-07-20", "x");
        mockMvc.perform(post("/api/mock/finance/reset")
                        .param("tenantId", "default")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("reset"));
        mockMvc.perform(get("/api/mock/finance/snapshot")
                        .param("userId", "u-bob")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$.data.expenses.length()").value(0));
    }

    @Test
    void patchExpenseStatus_updates() throws Exception {
        mockMvc.perform(patch("/api/mock/finance/expenses/exp-a1")
                        .param("userId", "u-alice")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"));
    }
}
