package com.sunshine.rag.admin;

import com.sunshine.rag.config.RagAdminProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminTokenFilterTest {

    @Mock
    private FilterChain chain;

    private AdminTokenFilter filter;

    @BeforeEach
    void setUp() {
        RagAdminProperties props = new RagAdminProperties();
        props.setToken("secret");
        filter = new AdminTokenFilter(props);
    }

    @Test
    void passesNonAdminPaths() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/rag/search");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void rejectsInvalidAdminToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/rag/admin/rebuild");
        req.addHeader("X-Admin-Token", "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("admin token invalid");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void allowsValidAdminToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/rag/admin/rebuild");
        req.addHeader("X-Admin-Token", "secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, chain);
        verify(chain).doFilter(req, res);
    }
}
