package com.cretas.aims.filter;

import com.cretas.aims.audit.RequestAuditSupport;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterContractTest {

    @Test
    void invalidHeaderIsReplacedAndUnverifiedJwtClaimsAreIgnored() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mobile/F006/materials");
        request.addHeader("X-Correlation-ID", "bad\nheader");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJub25lIn0.eyJ1c2VySWQiOjcsImZhY3RvcnlJZCI6IkYwMDYifQ.");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(((MockHttpServletResponse) res).getHeader("X-Correlation-ID"))
                    .matches("[A-Za-z0-9-]{36}");
            assertThat(RequestAuditSupport.userId((MockHttpServletRequest) req)).isNull();
            assertThat(RequestAuditSupport.trustedFactoryId((MockHttpServletRequest) req)).isNull();
        });

        assertThat(response.getHeader("X-Correlation-ID")).doesNotContain("\n", "\r");
    }

    @Test
    void verifiedRequestAttributesAreAvailableToCompletionAudit() throws IOException, ServletException {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mobile/F006/materials");
        request.addHeader("X-Correlation-ID", "corr-safe-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute("factoryId", "F006");
            req.setAttribute("userId", 7L);
            req.setAttribute("username", "operator");
        });

        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("corr-safe-1");
    }
}
