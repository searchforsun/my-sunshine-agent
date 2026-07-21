package com.sunshine.finance;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.finance.controller.BizFinanceController;
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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BizFinanceController.class)
@AutoConfigureDataJpa
@Import({FinanceBizService.class, GlobalExceptionHandler.class})
@EntityScan("com.sunshine.finance.entity")
@EnableJpaRepositories("com.sunshine.finance.repo")
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/data-finance.sql")
class BizFinanceControllerTest {

    static final String TOKEN = "sunshine-biz-admin-dev";
    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listExpenses_requiresAdminToken() throws Exception {
        mockMvc.perform(get("/api/biz/finance/expenses"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listExpenses_withToken_ok() throws Exception {
        mockMvc.perform(get("/api/biz/finance/expenses").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void listExpenses_filterByUserId() throws Exception {
        mockMvc.perform(get("/api/biz/finance/expenses")
                        .header("X-Admin-Token", TOKEN)
                        .param("userId", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(ALICE));
    }

    @Test
    void createUpdateDelete_expense() throws Exception {
        String body = """
                {"userId":"%s","category":"办公用品","amount":12.50,"occurredOn":"2026-07-21","status":"pending","remark":"胶带"}
                """.formatted(BOB);
        String id = mockMvc.perform(post("/api/biz/finance/expenses")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(startsWith("exp-")))
                .andExpect(jsonPath("$.data.userId").value(BOB))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String expenseId = id.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(put("/api/biz/finance/expenses/" + expenseId)
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","category":"办公用品","amount":15.00,"occurredOn":"2026-07-21","status":"approved","remark":"胶带×2"}
                                """.formatted(BOB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"))
                .andExpect(jsonPath("$.data.amount").value(15.00));

        mockMvc.perform(delete("/api/biz/finance/expenses/" + expenseId)
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/biz/finance/expenses")
                        .header("X-Admin-Token", TOKEN)
                        .param("userId", BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void createUpdateDelete_inbox() throws Exception {
        String body = """
                {"userId":"%s","title":"发票核验","status":"pending","amount":99.00}
                """.formatted(BOB);
        String raw = mockMvc.perform(post("/api/biz/finance/inbox")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(startsWith("inbox-")))
                .andExpect(jsonPath("$.data.userId").value(BOB))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String itemId = raw.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(put("/api/biz/finance/inbox/" + itemId)
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","title":"发票已核验","status":"done","amount":99.00}
                                """.formatted(BOB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("done"))
                .andExpect(jsonPath("$.data.title").value("发票已核验"));

        mockMvc.perform(delete("/api/biz/finance/inbox/" + itemId)
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void listInbox_withToken_ok() throws Exception {
        mockMvc.perform(get("/api/biz/finance/inbox").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)));
    }
}
