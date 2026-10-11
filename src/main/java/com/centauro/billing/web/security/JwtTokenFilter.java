package com.centauro.billing.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Valida el JWT HS256 de auth-service. Sin header sigue la cadena (el entry point responde 401);
 * con un token inválido responde 401 en el acto. No es {@code @Component}: lo instancia {@code SecurityConfig}.
 */
public class JwtTokenFilter extends OncePerRequestFilter {

    static final int MIN_SECRET_BYTES = 32;
    private static final String BEARER = "Bearer ";

    private final SecretKey key;
    private final SecurityErrorWriter errorWriter;

    public JwtTokenFilter(String secret, SecurityErrorWriter errorWriter) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos " + MIN_SECRET_BYTES + " bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }
        JwtClaims claims;
        try {
            Claims parsed = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(header.substring(BEARER.length()).trim())
                    .getPayload();
            claims = JwtClaims.from(parsed);
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            errorWriter.unauthorized(request, response, "INVALID_TOKEN");
            return;
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (claims.role() != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + claims.role()));
        }
        claims.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, authorities));
        chain.doFilter(request, response);
    }
}