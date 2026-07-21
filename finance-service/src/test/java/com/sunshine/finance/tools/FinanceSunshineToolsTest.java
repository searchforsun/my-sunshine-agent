package com.sunshine.finance.tools;

import com.sunshine.finance.store.TenantUserStore;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Autowired
    private TenantUserStore store;

    @SpringBootApplication
    @Import({SunshineToolAutoConfiguration.class, FinanceSunshineTools.class, TenantUserStore.class})
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
    void listMyExpenses_withAliceContext_returnsSeed() {
        ToolInvocationContext.set("default", "u-alice");
        String result = financeSunshineTools.listMyExpenses("pending");
        assertThat(result).contains("共 1 条报销单")
                .contains("[exp-a1]")
                .contains("市内交通");
    }

    @Test
    void listMyExpenses_withoutContext_throws() {
        ToolInvocationContext.clear();
        assertThatThrownBy(() -> financeSunshineTools.listMyExpenses(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x-user-id");
    }

    @Test
    void invokeWithoutUserHeader_returnsFailure() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/list_my_expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("x-user-id")));
    }

    @Test
    void invokeWithAliceHeader_returnsResult() throws Exception {
        mockMvc.perform(post("/sunshine/tools/invoke/list_my_expenses")
                        .header("x-user-id", "u-alice")
                        .header("x-tenant-id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("共 1 条报销单")));
    }

    @Test
    void catalogReturnsNewFinanceTools() throws Exception {
        mockMvc.perform(get("/sunshine/tools/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value("sunshine-finance"))
                .andExpect(jsonPath("$.tools.length()").value(6))
                .andExpect(jsonPath("$.tools[?(@.name=='list_my_expenses')].displayName")
                        .value("查询我的报销单"))
                .andExpect(jsonPath("$.tools[?(@.name=='get_expense_detail')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='submit_expense')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='list_my_finance_inbox')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='get_finance_inbox_item')]").exists())
                .andExpect(jsonPath("$.tools[?(@.name=='summarize_my_expenses')]").exists());
    }

    @Test
    void getExpenseDetail_crossUser_notFound() {
        ToolInvocationContext.set("default", "u-bob");
        assertThat(financeSunshineTools.getExpenseDetail("exp-a1"))
                .contains("未找到");
    }

    @Test
    void submitExpense_createsForCurrentUser() {
        ToolInvocationContext.set("default", "u-bob");
        String result = financeSunshineTools.submitExpense("办公用品", "12.5", "2026-07-20", "笔");
        assertThat(result).contains("已提交报销单").contains("id=exp-");
        assertThat(store.listExpenses("default", "u-bob", null)).hasSize(1);
    }
}
