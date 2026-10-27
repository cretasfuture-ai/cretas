package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.impl.smartbi.FactoryChartAnalysisFailureRelay;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.smartbi.FactoryChartAnalysisRequest;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fixed authenticated HTTP entry for factory SmartBI analysis. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi")
@RequiredArgsConstructor
public class FactoryChartAnalysisController {

    private static final String TOOL_NAME = "smartbi_factory_analysis";
    private static final String DESCRIPTOR_VERSION = "1.0.0";
    private static final Pattern FACTORY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern USER = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern ROLE = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final ToolExecutionGateway gateway;
    private final AuthenticatedToolPrincipalFactory principalFactory;
    private final ObjectMapper objectMapper;
    private final FactoryChartAnalysisFailureRelay failureRelay;

    @PostMapping("/factory-analysis")
    @RequirePermission({"analytics:read"})
    public ApiResponse<Object> analyze(
            @PathVariable String factoryId,
            @Valid @RequestBody FactoryChartAnalysisRequest request,
            HttpServletRequest httpRequest) {
        String trustedFactory = attribute(httpRequest, "factoryId");
        String trustedUser = attribute(httpRequest, "userId");
        String trustedRole = attribute(httpRequest, "role");
        if (trustedFactory == null || trustedUser == null || trustedRole == null) {
            throw new BusinessException(401, "Trusted authentication context is required");
        }
        String role = trustedRole.toLowerCase(Locale.ROOT);
        if (!factoryId.equals(trustedFactory) || !FACTORY.matcher(factoryId).matches()
                || !USER.matcher(trustedUser).matches() || !ROLE.matcher(role).matches()) {
            throw new BusinessException(403, "Authenticated tenant or identity is invalid");
        }
        long userId;
        try {
            userId = Long.parseLong(trustedUser);
        } catch (NumberFormatException ex) {
            throw new BusinessException(403, "Authenticated identity is invalid");
        }
        ExecutionPrincipal principal = principalFactory.create(factoryId, userId, role);
        String requestId = "factory-analysis-" + UUID.randomUUID();
        String correlationId = attribute(httpRequest, "X-Correlation-ID");
        if (correlationId == null) correlationId = requestId;
        try {
            ToolExecutionResult result = gateway.execute(new ToolExecutionCommand(
                    requestId, correlationId, requestId + "-trace", TOOL_NAME, DESCRIPTOR_VERSION,
                    objectMapper.valueToTree(request), principal, ToolExecutionSource.HTTP_CONTROLLER,
                    ToolExecutionMode.EXECUTE, java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty(), Instant.now().plusSeconds(90)));
            if (result.status() != ToolExecutionStatus.SUCCEEDED || !result.payload().isObject()) {
                var relayed = failureRelay.consume(requestId);
                if (relayed.isPresent() && java.util.Set.of(403, 409, 410, 422)
                        .contains(relayed.get().statusCode())) {
                    throw new BusinessException(
                            relayed.get().statusCode(), relayed.get().message());
                }
                int code = result.status() == ToolExecutionStatus.DENIED ? 403 : 400;
                throw new BusinessException(code, result.message());
            }
            Map<String, Object> payload = objectMapper.convertValue(
                    result.payload(), new TypeReference<Map<String, Object>>() {});
            Object data = payload.get("data");
            // The tool envelope contains the Python envelope. Expose its data object directly so the
            // mobile ApiResponse has one {success,data,message} layer, with sourceErrors untouched.
            if (!(data instanceof Map<?, ?> pythonEnvelope)
                    || !(pythonEnvelope.get("data") instanceof Map<?, ?>)) {
                throw new BusinessException(502, "Factory analysis returned an invalid response");
            }
            data = ((Map<?, ?>) data).get("data");
            return ApiResponse.success(data);
        } finally {
            failureRelay.clear(requestId);
        }
    }

    private static String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
