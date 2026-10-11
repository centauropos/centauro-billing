package com.centauro.billing.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** API key de servicio a servicio. No es {@code @Component}: lo instancia {@code SecurityConfig}. */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String INTERNAL_PRINCIPAL = "internal-service";

    private final byte[] expectedKey;
    private final SecurityErrorWriter errorWriter;

    public InternalApiKeyFilter(String apiKey, SecurityErrorWriter errorWriter) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("BILLING_INTERNAL_API_KEY es obligatoria");
        }
        this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.clearContext();
            errorWriter.unauthorized(request, response, "INVALID_API_KEY");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                INTERNAL_PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
        chain.doFilter(request, response);
    }
}