package com.sunshine.oa;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.oa.controller.BizOaController;
import com.sunshine.oa.service.OaBizService;
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

@WebMvcTest(controllers = BizOaController.class)
@AutoConfigureDataJpa
@Import({OaBizService.class, GlobalExceptionHandler.class})
@EntityScan("com.sunshine.oa.entity")
@EnableJpaRepositories("com.sunshine.oa.repo")
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/data-oa.sql")
class BizOaControllerTest {

    static final String TOKEN = "sunshine-biz-admin-dev";
    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listTasks_requiresAdminToken() throws Exception {
        mockMvc.perform(get("/api/biz/oa/tasks"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listTasks_withToken_ok() throws Exception {
        mockMvc.perform(get("/api/biz/oa/tasks").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(3)));
    }

    @Test
    void listTasks_filterByAssignee() throws Exception {
        mockMvc.perform(get("/api/biz/oa/tasks")
                        .header("X-Admin-Token", TOKEN)
                        .param("userId", BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].assigneeUserId").value(BOB));
    }

    @Test
    void createUpdateDelete_task() throws Exception {
        String body = """
                {"assigneeUserId":"%s","title":"用印审批","category":"seal","status":"pending"}
                """.formatted(ALICE);
        String raw = mockMvc.perform(post("/api/biz/oa/tasks")
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(startsWith("task-")))
                .andExpect(jsonPath("$.data.assigneeUserId").value(ALICE))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = raw.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(put("/api/biz/oa/tasks/" + taskId)
                        .header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assigneeUserId":"%s","title":"用印已完成","category":"seal","status":"done"}
                                """.formatted(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("done"))
                .andExpect(jsonPath("$.data.title").value("用印已完成"));

        mockMvc.perform(delete("/api/biz/oa/tasks/" + taskId)
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/biz/oa/tasks")
                        .header("X-Admin-Token", TOKEN)
                        .param("userId", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
