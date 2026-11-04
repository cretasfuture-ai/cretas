package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.intent.catalog.TraceCodePolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Reads the actual shared trace-code policy; it does not consume a sequence. */
@Component
public class TraceCodeFormatTool extends AbstractBusinessTool {
    @Override public String getToolName() { return "trace_code_format"; }
    @Override public String getDescription() { return "读取工厂统一溯源码格式，不生成或占用新的溯源码"; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }
    @Override protected List<String> getRequiredParameters() { return List.of(); }
    @Override protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                                      Map<String, Object> context) {
        return TraceCodePolicy.describe(factoryId);
    }
    @Override public AccessMode getAccessMode() { return AccessMode.READ; }
    @Override public ActionType getActionType() { return ActionType.READ; }
}
