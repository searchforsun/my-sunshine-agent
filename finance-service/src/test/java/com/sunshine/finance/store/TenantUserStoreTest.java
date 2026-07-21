package com.sunshine.finance.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.finance.model.ExpenseRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantUserStoreTest {

    private TenantUserStore store;

    @BeforeEach
    void setUp() {
        store = new TenantUserStore(new ObjectMapper());
        store.init();
    }

    @Test
    void aliceAndBob_haveDifferentExpenseLists() {
        List<ExpenseRecord> alice = store.listExpenses("default", "u-alice", null);
        List<ExpenseRecord> bob = store.listExpenses("default", "u-bob", null);
        assertThat(alice).isNotEmpty();
        assertThat(bob).isEmpty();
        assertThat(alice).extracting(ExpenseRecord::id).contains("exp-a1");
    }

    @Test
    void findExpense_crossUser_returnsEmpty() {
        assertThat(store.findExpense("default", "u-bob", "exp-a1")).isEmpty();
        assertThat(store.findExpense("default", "u-alice", "exp-a1")).isPresent();
    }

    @Test
    void submitExpense_addsToUser() {
        ExpenseRecord created = store.submitExpense(
                "default", "u-bob", "办公用品", new BigDecimal("42.00"), "2026-07-20", "打印纸");
        assertThat(created.id()).startsWith("exp-");
        assertThat(created.status()).isEqualTo("pending");
        assertThat(store.listExpenses("default", "u-bob", null))
                .extracting(ExpenseRecord::id)
                .contains(created.id());
        assertThat(store.findExpense("default", "u-alice", created.id())).isEmpty();
    }

    @Test
    void listExpenses_filtersByStatus() {
        assertThat(store.listExpenses("default", "u-alice", "pending"))
                .extracting(ExpenseRecord::status)
                .containsOnly("pending");
    }

    @Test
    void reset_reloadsSeedAndDropsRuntimeWrites() {
        store.submitExpense("default", "u-bob", "办公用品", new BigDecimal("1.00"), "2026-07-20", null);
        assertThat(store.listExpenses("default", "u-bob", null)).isNotEmpty();
        store.reset("default");
        assertThat(store.listExpenses("default", "u-bob", null)).isEmpty();
        assertThat(store.findExpense("default", "u-alice", "exp-a1")).isPresent();
    }
}
