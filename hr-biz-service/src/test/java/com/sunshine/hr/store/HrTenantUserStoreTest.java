package com.sunshine.hr.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.hr.model.LeaveBalance;
import com.sunshine.hr.model.LeaveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HrTenantUserStoreTest {

    private HrTenantUserStore store;

    @BeforeEach
    void setUp() {
        store = new HrTenantUserStore(new ObjectMapper());
        store.init();
    }

    @Test
    void aliceAndBob_haveDifferentLeaveBalances() {
        LeaveBalance alice = store.getLeaveBalance("default", "u-alice", 2026).orElseThrow();
        LeaveBalance bob = store.getLeaveBalance("default", "u-bob", 2026).orElseThrow();
        assertThat(alice.qingsong()).isEqualTo(12);
        assertThat(alice.annual()).isEqualTo(5);
        assertThat(bob.qingsong()).isEqualTo(0);
        assertThat(bob.annual()).isEqualTo(10);
    }

    @Test
    void aliceAndBob_haveDifferentLeaveRequests() {
        List<LeaveRequest> alice = store.listLeaveRequests("default", "u-alice", null);
        List<LeaveRequest> bob = store.listLeaveRequests("default", "u-bob", null);
        assertThat(alice).isNotEmpty();
        assertThat(bob).isEmpty();
        assertThat(alice).extracting(LeaveRequest::id).contains("leave-a1", "leave-a2");
    }

    @Test
    void listLeaveRequests_crossUser_isolation() {
        assertThat(store.listLeaveRequests("default", "u-bob", null))
                .extracting(LeaveRequest::id)
                .doesNotContain("leave-a1");
    }

    @Test
    void submitLeaveRequest_addsToUserOnly() {
        LeaveRequest created = store.submitLeaveRequest(
                "default", "u-bob", "annual", "2026-07-22", "2026-07-23", "事假");
        assertThat(created.id()).startsWith("leave-");
        assertThat(created.status()).isEqualTo("pending");
        assertThat(store.listLeaveRequests("default", "u-bob", null))
                .extracting(LeaveRequest::id)
                .contains(created.id());
        assertThat(store.listLeaveRequests("default", "u-alice", null))
                .extracting(LeaveRequest::id)
                .doesNotContain(created.id());
    }

    @Test
    void getAttendanceMonth_aliceJuly_hasFrostLedger() {
        assertThat(store.getAttendanceMonth("default", "u-alice", "2026-07"))
                .isPresent()
                .get()
                .satisfies(a -> {
                    assertThat(a.lateCount()).isEqualTo(2);
                    assertThat(a.frostLedgerSummary()).contains("霜降");
                });
        assertThat(store.getAttendanceMonth("default", "u-carol", "2026-07")).isEmpty();
    }

    @Test
    void listLeaveRequests_filtersByStatus() {
        assertThat(store.listLeaveRequests("default", "u-alice", "pending"))
                .extracting(LeaveRequest::status)
                .containsOnly("pending");
    }

    @Test
    void reset_reloadsSeedAndDropsRuntimeWrites() {
        store.submitLeaveRequest("default", "u-bob", "annual", "2026-07-22", "2026-07-22", "x");
        assertThat(store.listLeaveRequests("default", "u-bob", null)).isNotEmpty();
        store.reset("default");
        assertThat(store.listLeaveRequests("default", "u-bob", null)).isEmpty();
        assertThat(store.getLeaveBalance("default", "u-alice", 2026)).isPresent();
    }
}
