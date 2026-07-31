package com.zzp.aiagent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class DemoCaseHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Demo-Case-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        DemoCaseContext.bind(request.getHeader(HEADER));
        try {
            filterChain.doFilter(request, response);
        } finally {
            DemoCaseContext.clear();
        }
    }
}
