package com.centauro.billing.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiKeyFilterTest {

    private static final String KEY = "internal-key-123";

    private final SecurityErrorWriter writer = new SecurityErrorWriter(
            new ObjectMapper().registerModule(new JavaTimeModule()), clockAt(NOW), AR);
    private final InternalApiKeyFilter filter = new InternalApiKeyFilter(KEY, writer);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctKeyPasses() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(InternalApiKeyFilter.HEADER, KEY);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(InternalApiKeyFilter.INTERNAL_PRINCIPAL);
    }

    @Test
    void wrongKeyIs401() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(InternalApiKeyFilter.HEADER, KEY + "x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"INVALID_API_KEY\"")
                .contains("\"path\":\"/internal/v1/tenants/t-1/access\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingKeyIs401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void emptyConfiguredKeyFailsAtStartup(String configured) {
        assertThatThrownBy(() -> new InternalApiKeyFilter(configured, writer))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/internal/v1/tenants/t-1/access");
    }
}
