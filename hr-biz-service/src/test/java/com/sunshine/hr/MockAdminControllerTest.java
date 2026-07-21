package com.sunshine.hr;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.hr.controller.MockAdminController;
import com.sunshine.hr.store.HrTenantUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MockAdminController.class)
@Import({HrTenantUserStore.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class MockAdminControllerTest {

    private static final String TOKEN = "sunshine-mock-admin-dev";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HrTenantUserStore store;

    @BeforeEach
    void reset() {
        store.reset("default");
    }

    @Test
    void users_missingToken_401() throws Exception {
        mockMvc.perform(get("/api/mock/hr/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void users_listsSeedUsers() throws Exception {
        mockMvc.perform(get("/api/mock/hr/users").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0]").value("u-alice"));
    }

    @Test
    void snapshot_returnsAliceLeave() throws Exception {
        mockMvc.perform(get("/api/mock/hr/snapshot")
                        .param("userId", "u-alice")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leaveBalance").exists())
                .andExpect(jsonPath("$.data.leaveRequests").isArray());
    }

    @Test
    void reset_ok() throws Exception {
        mockMvc.perform(post("/api/mock/hr/reset").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("reset"));
    }
}
