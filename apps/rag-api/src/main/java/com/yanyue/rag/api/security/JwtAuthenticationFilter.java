package com.yanyue.rag.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final DSLContext dsl;

    public JwtAuthenticationFilter(JwtService jwtService, DSLContext dsl) {
        this.jwtService = jwtService;
        this.dsl = dsl;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            jwtService.verify(authorization.substring(7)).flatMap(this::currentUser).ifPresent(user -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }

    private java.util.Optional<AuthenticatedUser> currentUser(AuthenticatedUser tokenUser) {
        return dsl.fetchOptional("""
                SELECT organization_id, username, role, auth_version
                FROM app_user
                WHERE id = ? AND organization_id = ? AND enabled = true
                """, tokenUser.userId(), tokenUser.organizationId()).flatMap(record -> {
            var authVersion = record.get("auth_version", Long.class);
            if (authVersion == null || authVersion != tokenUser.authVersion()) return java.util.Optional.empty();
            return java.util.Optional.of(new AuthenticatedUser(
                    tokenUser.userId(), record.get("organization_id", UUID.class),
                    record.get("username", String.class), record.get("role", String.class), authVersion));
        });
    }
}
