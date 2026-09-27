package com.cretas.aims.audit.client;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityEventBodyLimitContractTest {
    private final ClientErrorBodyLimitFilter filter = new ClientErrorBodyLimitFilter();

    @Test
    void chunkedActivityBatchIsCappedBeforeJsonBinding() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/activity-events");
        request.setContent(new byte[ClientErrorBodyLimitFilter.MAX_ACTIVITY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void existingClientErrorLimitRemainsEightKilobytes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/client-errors");
        request.setContent(new byte[ClientErrorBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void validBatchReachesControllerUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/activity-events");
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        request.setContent(body);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(((HttpServletRequest) chain.getRequest()).getInputStream().readAllBytes()).isEqualTo(body);
    }
}
