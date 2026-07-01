package com.sunshine.rag.admin;

import com.sunshine.rag.config.RagAdminProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 统一校验 `/api/rag/admin/**` 的 X-Admin-Token */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class AdminTokenFilter extends OncePerRequestFilter {
    private final RagAdminProperties adminProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!req.getRequestURI().startsWith("/api/rag/admin/")) {
            chain.doFilter(req, res);
            return;
        }
        String required = adminProperties.getToken();
        if (required == null || required.isBlank()) {
            chain.doFilter(req, res);
            return;
        }
        String token = req.getHeader("X-Admin-Token");
        if (!required.equals(token)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.getWriter().write("{\"code\":401,\"msg\":\"admin token invalid\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
