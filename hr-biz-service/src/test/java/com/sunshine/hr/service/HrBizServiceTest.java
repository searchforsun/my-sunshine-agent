package com.sunshine.hr.service;

import com.sunshine.hr.model.LeaveBalance;
import com.sunshine.hr.model.LeaveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(HrBizService.class)
@ActiveProfiles("test")
@Sql(scripts = "/data-hr.sql")
class HrBizServiceTest {

    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";
    static final String CAROL = "c3333333-3333-4333-c333-333333333333";

    @Autowired
    private HrBizService hrBizService;

    @Test
    void alice_2026_qingsongEquals12() {
        LeaveBalance alice = hrBizService.getLeaveBalance("default", ALICE, 2026).orElseThrow();
        assertThat(alice.qingsong()).isEqualTo(12);
        assertThat(alice.annual()).isEqualTo(5);
        assertThat(alice.compensatory()).isEqualTo(3);
    }

    @Test
    void bobAndAlice_haveDifferentLeaveBalances() {
        LeaveBalance bob = hrBizService.getLeaveBalance("default", BOB, 2026).orElseThrow();
        assertThat(bob.qingsong()).isEqualTo(0);
        assertThat(bob.annual()).isEqualTo(10);
    }

    @Test
    void aliceAndBob_haveDifferentLeaveRequests() {
        List<LeaveRequest> alice = hrBizService.listLeaveRequests("default", ALICE, null);
        List<LeaveRequest> bob = hrBizService.listLeaveRequests("default", BOB, null);
        assertThat(alice).isNotEmpty();
        assertThat(bob).isEmpty();
        assertThat(alice).extracting(LeaveRequest::id).contains("leave-a1", "leave-a2");
    }

    @Test
    void listLeaveRequests_crossUser_isolation() {
        assertThat(hrBizService.listLeaveRequests("default", BOB, null))
                .extracting(LeaveRequest::id)
                .doesNotContain("leave-a1");
    }

    @Test
    void submitLeaveRequest_addsToUserOnly() {
        LeaveRequest created = hrBizService.submitLeaveRequest(
                "default", BOB, "annual", "2026-07-22", "2026-07-23", "事假");
        assertThat(created.id()).startsWith("leave-");
        assertThat(created.status()).isEqualTo("pending");
        assertThat(hrBizService.listLeaveRequests("default", BOB, null))
                .extracting(LeaveRequest::id)
                .contains(created.id());
        assertThat(hrBizService.listLeaveRequests("default", ALICE, null))
                .extracting(LeaveRequest::id)
                .doesNotContain(created.id());
    }

    @Test
    void getAttendanceMonth_aliceJuly_hasFrostLedger() {
        assertThat(hrBizService.getAttendanceMonth("default", ALICE, "2026-07"))
                .isPresent()
                .get()
                .satisfies(a -> {
                    assertThat(a.lateCount()).isEqualTo(2);
                    assertThat(a.frostLedgerSummary()).contains("霜降");
                });
        assertThat(hrBizService.getAttendanceMonth("default", CAROL, "2026-07")).isEmpty();
    }

    @Test
    void listLeaveRequests_filtersByStatus() {
        assertThat(hrBizService.listLeaveRequests("default", ALICE, "pending"))
                .extracting(LeaveRequest::status)
                .containsOnly("pending");
    }

    @Test
    void adminListLeaveBalances_nonEmpty() {
        assertThat(hrBizService.adminListLeaveBalances("default", null)).isNotEmpty();
    }
}
