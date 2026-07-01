package com.sunshine.rag.admin.catalog;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.rag.admin.catalog.dto.CreateKbRequest;
import com.sunshine.rag.admin.catalog.dto.KbSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KbAdminControllerTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new KbAdminController(knowledgeBaseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createListAndSetDefault() throws Exception {
        KbSummary created = new KbSummary("finance", "财务库", "报销制度", false, "active");
        KbSummary defaulted = new KbSummary("finance", "财务库", "报销制度", true, "active");
        when(knowledgeBaseService.create(eq("default"), any(CreateKbRequest.class))).thenReturn(created);
        when(knowledgeBaseService.listByTenant("default")).thenReturn(List.of(
                new KbSummary("default", "默认知识库", null, false, "active"),
                created));
        when(knowledgeBaseService.setDefault("default", "finance")).thenReturn(defaulted);

        mockMvc.perform(post("/api/rag/admin/kbs")
                        .header("x-tenant-id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kbId":"finance","displayName":"财务库","description":"报销制度"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kbId").value("finance"));

        mockMvc.perform(get("/api/rag/admin/kbs").header("x-tenant-id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].kbId").value("finance"));

        mockMvc.perform(put("/api/rag/admin/kbs/finance/default").header("x-tenant-id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true));
    }
}
