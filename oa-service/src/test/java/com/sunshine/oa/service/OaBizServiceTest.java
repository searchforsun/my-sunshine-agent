package com.sunshine.oa.service;

import com.sunshine.oa.model.OaTask;
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
@Import(OaBizService.class)
@ActiveProfiles("test")
@Sql(scripts = "/data-oa.sql")
class OaBizServiceTest {

    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";
    static final String CAROL = "c3333333-3333-4333-c333-333333333333";

    @Autowired
    private OaBizService oaBizService;

    @Test
    void listTasks_bobHasTwoPending_aliceOne() {
        List<OaTask> bob = oaBizService.listTasks("default", BOB, "pending");
        List<OaTask> alice = oaBizService.listTasks("default", ALICE, "pending");
        assertThat(bob).hasSize(2);
        assertThat(bob).extracting(OaTask::id).containsExactly("task-b1", "task-b2");
        assertThat(alice).hasSize(1);
        assertThat(alice.get(0).id()).isEqualTo("task-a1");
    }

    @Test
    void listTasks_carolEmpty() {
        assertThat(oaBizService.listTasks("default", CAROL, "all")).isEmpty();
    }

    @Test
    void approveTask_bobCanApproveOwn() {
        assertThat(oaBizService.approveTask("default", BOB, "task-b1")).isPresent()
                .get()
                .extracting(OaTask::status)
                .isEqualTo("done");
        assertThat(oaBizService.listTasks("default", BOB, "pending"))
                .extracting(OaTask::id)
                .containsExactly("task-b2");
    }

    @Test
    void approveTask_aliceCannotApproveBobs() {
        assertThat(oaBizService.approveTask("default", ALICE, "task-b1")).isEmpty();
        assertThat(oaBizService.findTask("default", BOB, "task-b1"))
                .isPresent()
                .get()
                .extracting(OaTask::status)
                .isEqualTo("pending");
    }

    @Test
    void findTask_crossUser_returnsEmpty() {
        assertThat(oaBizService.findTask("default", ALICE, "task-b1")).isEmpty();
        assertThat(oaBizService.findTask("default", BOB, "task-b1")).isPresent();
    }
}
