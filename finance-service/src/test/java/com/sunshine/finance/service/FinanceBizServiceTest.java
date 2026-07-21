package com.sunshine.finance.service;

import com.sunshine.finance.model.ExpenseRecord;
import com.sunshine.finance.model.FinanceInboxItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FinanceBizService.class)
@ActiveProfiles("test")
@Sql(scripts = "/data-finance.sql")
class FinanceBizServiceTest {

    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";
    static final String CAROL = "c3333333-3333-4333-c333-333333333333";

    @Autowired
    private FinanceBizService financeBizService;

    @Test
    void listExpenses_aliceHasTwo_bobEmpty() {
        List<ExpenseRecord> alice = financeBizService.listExpenses("default", ALICE, "all");
        List<ExpenseRecord> bob = financeBizService.listExpenses("default", BOB, "all");
        assertThat(alice).hasSize(2);
        assertThat(alice).extracting(ExpenseRecord::id).contains("exp-a1", "exp-a2");
        assertThat(bob).isEmpty();
    }

    @Test
    void findExpense_crossUser_returnsEmpty() {
        assertThat(financeBizService.findExpense("default", BOB, "exp-a1")).isEmpty();
        assertThat(financeBizService.findExpense("default", ALICE, "exp-a1")).isPresent();
    }

    @Test
    void submitExpense_addsToUser() {
        ExpenseRecord created = financeBizService.submitExpense(
                "default", BOB, "办公用品", new BigDecimal("42.00"), "2026-07-20", "打印纸");
        assertThat(created.id()).startsWith("exp-");
        assertThat(created.status()).isEqualTo("pending");
        assertThat(financeBizService.listExpenses("default", BOB, null))
                .extracting(ExpenseRecord::id)
                .contains(created.id());
        assertThat(financeBizService.findExpense("default", ALICE, created.id())).isEmpty();
    }

    @Test
    void listExpenses_filtersByStatus() {
        assertThat(financeBizService.listExpenses("default", ALICE, "pending"))
                .extracting(ExpenseRecord::status)
                .containsOnly("pending");
    }

    @Test
    void listInbox_carolSeesItem() {
        List<FinanceInboxItem> carol = financeBizService.listInbox("default", CAROL, null);
        assertThat(carol).hasSize(1);
        assertThat(carol.get(0).id()).isEqualTo("inbox-c1");
    }

    @Test
    void summarizeExpenses_groupsByStatus() {
        assertThat(financeBizService.summarizeExpenses("default", ALICE, "all"))
                .isNotEmpty()
                .anyMatch(s -> "pending".equals(s.status()) && s.count() == 1);
    }
}
