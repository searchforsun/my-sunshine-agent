package com.sunshine.finance;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.finance.controller.FinanceController;
import com.sunshine.finance.service.FinanceBizService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FinanceController.class)
@AutoConfigureDataJpa
@Import({FinanceBizService.class, GlobalExceptionHandler.class})
@EntityScan("com.sunshine.finance.entity")
@EnableJpaRepositories("com.sunshine.finance.repo")
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/data-finance.sql")
class FinanceControllerTest {

    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";
    static final String CAROL = "c3333333-3333-4333-c333-333333333333";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listExpenses_requiresUserHeader() throws Exception {
        mockMvc.perform(get("/api/finance/expenses"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listExpenses_aliceSeesSeed() throws Exception {
        mockMvc.perform(get("/api/finance/expenses").header("x-user-id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("exp-a1"));
    }

    @Test
    void listExpenses_bobSeesEmpty() throws Exception {
        mockMvc.perform(get("/api/finance/expenses").header("x-user-id", BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getExpense_crossUser_notFound() throws Exception {
        mockMvc.perform(get("/api/finance/expenses/exp-a1").header("x-user-id", BOB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void submitExpense_addsForUser() throws Exception {
        mockMvc.perform(post("/api/finance/expenses")
                        .header("x-user-id", BOB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"办公用品","amount":42.00,"occurredOn":"2026-07-20","remark":"打印纸"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(org.hamcrest.Matchers.startsWith("exp-")))
                .andExpect(jsonPath("$.data.status").value("pending"));
        mockMvc.perform(get("/api/finance/expenses").header("x-user-id", BOB))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void summarizeExpenses_returnsRows() throws Exception {
        mockMvc.perform(get("/api/finance/expenses/summary").header("x-user-id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data[0].status").exists());
    }

    @Test
    void listInbox_carolSeesItem() throws Exception {
        mockMvc.perform(get("/api/finance/inbox").header("x-user-id", CAROL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("inbox-c1"));
    }

    @Test
    void getInboxItem_returnsDetail() throws Exception {
        mockMvc.perform(get("/api/finance/inbox/inbox-a1").header("x-user-id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("报销单待补充发票"));
    }
}
