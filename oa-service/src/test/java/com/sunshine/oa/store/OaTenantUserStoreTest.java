package com.sunshine.oa.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.oa.model.OaTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OaTenantUserStoreTest {

    private OaTenantUserStore store;

    @BeforeEach
    void setUp() {
        store = new OaTenantUserStore(new ObjectMapper());
        store.init();
    }

    @Test
    void aliceDoesNotSeeBobsTasks() {
        List<OaTask> alice = store.listTasks("default", "u-alice", null);
        List<OaTask> bob = store.listTasks("default", "u-bob", null);
        assertThat(alice).extracting(OaTask::id).containsExactly("task-a1");
        assertThat(bob).extracting(OaTask::id).containsExactly("task-b1", "task-b2");
        assertThat(alice).extracting(OaTask::id).doesNotContain("task-b1", "task-b2");
    }

    @Test
    void bobCanApproveOwnTask() {
        assertThat(store.approveTask("default", "u-bob", "task-b1")).isPresent()
                .get()
                .extracting(OaTask::status)
                .isEqualTo("done");
        assertThat(store.findTask("default", "u-bob", "task-b1"))
                .isPresent()
                .get()
                .extracting(OaTask::status)
                .isEqualTo("done");
        assertThat(store.listTasks("default", "u-bob", "pending"))
                .extracting(OaTask::id)
                .containsExactly("task-b2");
    }

    @Test
    void aliceCannotApproveBobsTask() {
        assertThat(store.approveTask("default", "u-alice", "task-b1")).isEmpty();
        assertThat(store.findTask("default", "u-bob", "task-b1"))
                .isPresent()
                .get()
                .extracting(OaTask::status)
                .isEqualTo("pending");
    }

    @Test
    void listTasks_filtersByStatus() {
        assertThat(store.listTasks("default", "u-bob", "pending"))
                .extracting(OaTask::status)
                .containsOnly("pending");
    }

    @Test
    void reset_reloadsSeedAndDropsRuntimeWrites() {
        store.approveTask("default", "u-bob", "task-b1");
        assertThat(store.findTask("default", "u-bob", "task-b1").orElseThrow().status()).isEqualTo("done");
        store.reset("default");
        assertThat(store.findTask("default", "u-bob", "task-b1").orElseThrow().status()).isEqualTo("pending");
        assertThat(store.findTask("default", "u-alice", "task-a1")).isPresent();
    }
}
