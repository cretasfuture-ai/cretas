package com.cretas.aims.audit;

import com.cretas.aims.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class RequestAuditSupportContractTest {

    @Test
    void onlyJwtInterceptorAttributesCanEstablishTrustedFactoryIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/materials/42?secret=leak");
        request.setAttribute("factoryId", "F999");
        request.setAttribute("userId", 7L);
        assertThat(RequestAuditSupport.trustedFactoryId(request)).isNull();
        assertThat(RequestAuditSupport.authenticated(request)).isFalse();

        request.setAttribute("factoryId", "F006");
        assertThat(RequestAuditSupport.trustedFactoryId(request)).isEqualTo("F006");
        assertThat(RequestAuditSupport.authenticated(request)).isTrue();
        assertThat(RequestAuditSupport.route(request)).isEqualTo("/api/mobile/F006/materials/42");
    }

    @Test
    void correlationAndExceptionTextAreBoundedAndSecretsAreRedacted() {
        assertThat(RequestAuditSupport.sanitizeCorrelationId("corr-01_abc:9")).isEqualTo("corr-01_abc:9");
        assertThat(RequestAuditSupport.sanitizeCorrelationId("bad\ncorrelation")).isNull();
        assertThat(RequestAuditSupport.sanitizeCorrelationId("x".repeat(65))).isNull();
        String sensitive = "Authorization: Bearer abc.def password=hunter2 token=plain "
                + "\\\"password\\\":\\\"json-secret\\\"";
        assertThat(RequestAuditSupport.sanitizeText(sensitive, 200))
                .doesNotContain("abc.def", "hunter2", "plain", "json-secret")
                .contains("[REDACTED]");
    }

    @Test
    void unifiedResponseFailureIsNotReportedAsSuccessful() {
        assertThat(RequestAuditSupport.responseOutcome(ApiResponse.success("ok"))).isTrue();
        ApiResponse<String> failure = ApiResponse.error("password=hunter2");
        failure.setErrorCode("BAD_INPUT");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/materials");
        RequestAuditSupport.captureResponseOutcome(request, failure);
        assertThat(RequestAuditSupport.responseOutcome(failure)).isFalse();
        assertThat(RequestAuditSupport.responseError(request)).doesNotContain("hunter2");
        assertThat(RequestAuditSupport.responseErrorCode(request)).isEqualTo("BAD_INPUT");
    }

    @Test
    void allPostsAreRequestAuditsWithoutClaimingBusinessCreation() throws Exception {
        HandlerMethod query = new HandlerMethod(new AuditController(), "search");
        HandlerMethod create = new HandlerMethod(new AuditController(), "create");
        assertThat(RequestAuditSupport.isMutation("POST", "/api/mobile/F006/materials/search", query)).isTrue();
        assertThat(RequestAuditSupport.isMutation("POST", "/api/mobile/F006/materials", create)).isTrue();
        assertThat(RequestAuditSupport.action("POST")).isEqualTo("OTHER");
        assertThat(RequestAuditSupport.isMutation("POST", "/api/mobile/F006/process-checkin/checkin", null)).isTrue();
        assertThat(RequestAuditSupport.isMutation("POST", "/api/mobile/F006/work-reporting/checkout", null)).isTrue();
    }

    @Test
    void httpRejectionCannotBeOverriddenBySuccessfulEnvelope() {
        assertThat(RequestAuditSupport.responseOutcome(org.springframework.http.ResponseEntity
                .status(403).body(ApiResponse.success("ignored")))).isFalse();
        assertThat(RequestAuditSupport.sanitizeText("{\"password\":\"two word secret\"}", 200))
                .doesNotContain("two word secret").contains("REDACTED");
    }

    @RestController
    static class AuditController {
        @PostMapping("/api/mobile/{factoryId}/materials/search")
        public ApiResponse<String> search() { return ApiResponse.success("results"); }

        @PostMapping("/api/mobile/{factoryId}/materials")
        public ApiResponse<String> create() { return ApiResponse.success("created"); }
    }
}
