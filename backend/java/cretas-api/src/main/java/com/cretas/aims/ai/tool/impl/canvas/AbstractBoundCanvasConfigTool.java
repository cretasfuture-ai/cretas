package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.config.CanvasConfigCommandService;
import com.cretas.aims.service.config.CanvasConfigPlanBindingService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

/** Configuration writes have one mandatory bound writer; legacy unbound writes fail closed. */
abstract class AbstractBoundCanvasConfigTool extends AbstractBusinessTool {
    private static final String REJECTION_MARKER = "__verified_canvas_prewrite_rejection";
    private enum VerifiedRejection { STALE_PLAN }

    @Autowired
    private CanvasConfigCommandService commandExecutor;

    @Override
    protected final Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                                  Map<String, Object> context) {
        requireUserId(context, "变更 Canvas 配置");
        if (commandExecutor == null) throw new IllegalStateException("Canvas bound writer unavailable");
        try {
            Map<String, Object> readback = commandExecutor.execute(factoryId, getToolName(), params, context);
            return buildSimpleResult("Canvas 配置变更已完成并完成提交后回读", readback);
        } catch (CanvasConfigPlanBindingService.StalePlanException stale) {
            // Only the dedicated exception raised before apply() can produce this marker.
            // Commit/readback/database failures must keep the Gateway's UNKNOWN behavior.
            return Map.of(REJECTION_MARKER, VerifiedRejection.STALE_PLAN);
        }
    }

    @Override
    protected final String buildSuccessResult(Object data) {
        if (data instanceof Map<?, ?> result
                && result.get(REJECTION_MARKER) == VerifiedRejection.STALE_PLAN) {
            Map<String, Object> rejection = new LinkedHashMap<>();
            rejection.put("success", false);
            rejection.put("status", "NEED_MORE_INFO");
            rejection.put("needMoreInfo", true);
            rejection.put("requiresNewPreview", true);
            rejection.put("recoveryAction", "REPREVIEW");
            rejection.put("errorCode", 409);
            rejection.put("message", "配置已在预览后变化，本次未执行，请重新生成方案");
            rejection.put("error", rejection.get("message"));
            try {
                return objectMapper.writeValueAsString(rejection);
            } catch (Exception serializationFailure) {
                return buildErrorResult("无法确认执行结果，请核对实际记录，勿重复执行");
            }
        }
        return super.buildSuccessResult(data);
    }
}
