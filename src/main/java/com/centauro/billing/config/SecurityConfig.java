package com.centauro.billing.config;

import com.centauro.billing.web.security.BillingAuthorization;
import com.centauro.billing.web.security.InternalApiKeyFilter;
import com.centauro.billing.web.security.JwtTokenFilter;
import com.centauro.billing.web.security.SecurityErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Cuatro cadenas: interna (API key), webhooks (público con firma), API con JWT y el resto
 * (health y, si está habilitado, springdoc; todo lo demás denegado).
 * Los filtros se instancian acá y no son {@code @Component}, para que Spring Boot no los registre también
 * como filtros globales del servlet.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] DOCS = {"/swagger-ui.html", "/swagger-ui/**", "/api-docs", "/api-docs/**"};

    private final SecurityErrorWriter errorWriter;
    private final JwtTokenFilter jwtTokenFilter;
    private final InternalApiKeyFilter internalApiKeyFilter;
    private final boolean docsEnabled;

    public SecurityConfig(ObjectMapper objectMapper, Clock clock, BillingProperties properties,
                          @Value("${jwt.secret}") String jwtSecret,
                          @Value("${springdoc.api-docs.enabled:true}") boolean docsEnabled) {
        this.errorWriter = new SecurityErrorWriter(objectMapper, clock, ZoneId.of(properties.zone()));
        this.jwtTokenFilter = new JwtTokenFilter(jwtSecret, errorWriter);
        this.internalApiKeyFilter = new InternalApiKeyFilter(properties.internal().apiKey(), errorWriter);
        this.docsEnabled = docsEnabled;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
        stateless(http.securityMatcher("/internal/**"))
                .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * Público solo {@code POST /webhooks/mercadopago}: la autenticación es la firma {@code x-signature}, que
     * verifica el controller antes de guardar nada (401 si no es válida). El resto de {@code /webhooks/**}, denegado.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webhookChain(HttpSecurity http) throws Exception {
        stateless(http.securityMatcher("/webhooks/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/webhooks/mercadopago").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    /**
     * {@code /api/v1/platform/**} exige la authority {@code PLATFORM_ADMIN} acá y además el {@code @PreAuthorize}
     * de los controllers. Esa authority solo sale de {@code permissions}: el rol se mapea como {@code ROLE_<rol>}.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        stateless(http.securityMatcher("/api/**"))
                .cors(Customizer.withDefaults())
                .addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/platform/**").hasAuthority(BillingAuthorization.PLATFORM_ADMIN)
                        .requestMatchers("/api/v1/billing/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        stateless(http)
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll();
                    if (docsEnabled) {
                        auth.requestMatchers(DOCS).permitAll();
                    }
                    auth.anyRequest().denyAll();
                });
        return http.build();
    }

    private HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> errorWriter.unauthorized(req, res, "UNAUTHORIZED"))
                        .accessDeniedHandler((req, res, ex) -> errorWriter.forbidden(req, res)));
    }
}
