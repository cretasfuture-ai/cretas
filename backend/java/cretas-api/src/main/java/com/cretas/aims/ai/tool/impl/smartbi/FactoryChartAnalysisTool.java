package com.cretas.aims.ai.tool.impl.smartbi;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.gateway.EgressCapableTool;
import com.cretas.aims.ai.tool.gateway.ToolEgressPermit;
import com.cretas.aims.client.PythonSmartBIClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Governed, read-only factory chart selection and analysis tool. */
@Component
@RequiredArgsConstructor
public class FactoryChartAnalysisTool extends AbstractBusinessTool implements EgressCapableTool {

    private static final String TOOL_NAME = "smartbi_factory_analysis";
    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "question", "source", "uploadId", "year", "periodType", "startMonth",
            "endMonth", "analysisSubject", "contextId", "inspectionUploadId", "costUploadId");

    private final PythonSmartBIClient pythonClient;
    private final FactoryChartAnalysisFailureRelay failureRelay;

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "在当前工厂授权数据范围内，根据问题选择图表并生成有事实依据的分析。只读。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("question", Map.of("type", "string", "minLength", 1, "maxLength", 1000)),
                        Map.entry("source", Map.of("type", "string", "enum", List.of("business", "upload"))),
                        Map.entry("uploadId", Map.of("type", "integer")),
                        Map.entry("inspectionUploadId", Map.of("type", "integer", "minimum", 1)),
                        Map.entry("costUploadId", Map.of("type", "integer", "minimum", 1)),
                        Map.entry("year", Map.of("type", "integer")),
                        Map.entry("periodType", Map.of("type", "string", "enum", List.of("all", "year", "month_range"))),
                        Map.entry("startMonth", Map.of("type", "integer", "minimum", 1, "maximum", 12)),
                        Map.entry("endMonth", Map.of("type", "integer", "minimum", 1, "maximum", 12)),
                        Map.entry("analysisSubject", Map.of("type", "string", "maxLength", 200)),
                        Map.entry("contextId", Map.of("type", "string", "maxLength", 128))),
                "required", List.of("question"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("question");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        for (String key : params.keySet()) {
            if (!ALLOWED_PARAMETERS.contains(key)) {
                throw new IllegalArgumentException("unsupported factory analysis parameter");
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        copy(request, params, "question", "question");
        copy(request, params, "source", "source");
        copy(request, params, "uploadId", "upload_id");
        copy(request, params, "inspectionUploadId", "inspection_upload_id");
        copy(request, params, "costUploadId", "cost_upload_id");
        copy(request, params, "year", "year");
        copy(request, params, "periodType", "period_type");
        copy(request, params, "startMonth", "start_month");
        copy(request, params, "endMonth", "end_month");
        copy(request, params, "analysisSubject", "analysis_subject");
        copy(request, params, "contextId", "context_id");

        String userId = String.valueOf(context.get("userId"));
        String role = String.valueOf(context.get("userRole"));
        ToolEgressPermit permit = ToolEgressPermit.fromContext(context)
                .orElseThrow(() -> new SecurityException("factory analysis egress permit is required"));
        permit.requireExact(TOOL_NAME, getVersion(), permit.requestId(),
                "python-smartbi.factory-chart-analysis.v1");
        String correlationId = MDC.get("correlationId");
        try {
            return pythonClient.callFactoryChartAnalysis(
                    factoryId, userId, role,
                    correlationId == null || correlationId.isBlank() ? permit.requestId() : correlationId,
                    request);
        } catch (PythonSmartBIClient.FactoryAnalysisUpstreamException businessFailure) {
            failureRelay.record(permit.requestId(), businessFailure.statusCode(), businessFailure.getMessage());
            throw businessFailure;
        }
    }

    private static void copy(Map<String, Object> target, Map<String, Object> source,
                             String input, String output) {
        Object value = source.get(input);
        if (value != null) {
            target.put(output, value);
        }
    }

    @Override
    public ActionType getActionType() {
        return ActionType.ANALYZE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("analytics:read");
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("factory", "smartbi", "analytics");
    }

    @Override
    public Set<String> getEgressDestinationIds() {
        return Set.of("python-smartbi.factory-chart-analysis.v1");
    }
}
