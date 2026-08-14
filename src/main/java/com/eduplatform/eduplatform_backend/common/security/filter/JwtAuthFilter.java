package com.eduplatform.eduplatform_backend.common.security.filter;

import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.JwtService;
import com.eduplatform.eduplatform_backend.common.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Parses {@code Authorization: Bearer <jwt>}, validates the token, then populates the
 * SecurityContext with an {@link AuthenticatedPrincipal}. Authorities are the union of
 * {@code ROLE_<role>} and {@code <permission>} so both {@code hasRole()} and
 * {@code hasAuthority()} work in {@code @PreAuthorize}.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper mapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper mapper) {
        this.jwtService = jwtService;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(req, res);
            return;
        }
        String token = header.substring(BEARER.length()).trim();

        try {
            Claims claims = jwtService.parse(token);

            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            List<String> roles = JwtService.stringList(claims, "roles");
            List<String> perms = JwtService.stringList(claims, "perms");

            Set<String> authorities = new HashSet<>();
            roles.forEach(r -> authorities.add("ROLE_" + r));
            authorities.addAll(perms);

            AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                    userId, email, Set.copyOf(roles), Set.copyOf(perms));

            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null,
                    authorities.stream().map(SimpleGrantedAuthority::new).toList());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));

            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);

        } catch (AppException ex) {
            // Invalid / expired token — emit JSON 401 directly (the entry point handles "no token at all")
            SecurityContextHolder.clearContext();
            res.setStatus(ex.status().value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError body = ApiError.of(ex.status().value(), ex.code(), ex.getMessage(), req.getRequestURI());
            mapper.writeValue(res.getOutputStream(), body);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return Stream.of("/actuator", "/v3/api-docs", "/swagger-ui", "/swagger-ui.html")
                .anyMatch(p::startsWith);
    }
}
