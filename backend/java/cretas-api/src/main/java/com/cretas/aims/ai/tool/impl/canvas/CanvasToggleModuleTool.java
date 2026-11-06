package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.config.FactoryConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 模块开关工具
 *
 * 启用或禁用工厂的某个功能模块，通过 Canvas 配置服务实现模块级切换。
 *
 * Intent Code: CANVAS_TOGGLE_MODULE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasToggleModuleTool extends AbstractBoundCanvasConfigTool {

    @Override
    public String getToolName() {
        return "canvas_toggle_module";
    }

    @Override
    public String getDescription() {
        return "启用或禁用工厂的某个功能模块。适用场景：通过 Canvas 配置界面开关特定模块，如启用采购模块、禁用餐饮模块等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> moduleCode = new HashMap<>();
        moduleCode.put("type", "string");
        moduleCode.put("description", "模块代码，如 PURCHASE、SALES、EQUIPMENT、HR 等");
        properties.put("moduleCode", moduleCode);

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "是否启用该模块，true 为启用，false 为禁用");
        properties.put("enabled", enabled);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("moduleCode", "enabled"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("moduleCode", "enabled");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "moduleCode", "请问要操作哪个功能模块？请提供模块代码（如 PURCHASE、SALES、EQUIPMENT 等）。",
                "enabled", "请问是要启用还是禁用该模块？（true 为启用，false 为禁用）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "moduleCode", "模块代码",
                "enabled", "是否启用"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public ActionType getActionType() { return ActionType.UPDATE; }

    @Override
    public RiskLevel getRiskLevel() { return RiskLevel.LOW; }

    @Override
    public boolean requiresPermission() { return true; }

    @Override
    public Set<String> getRequiredPermissions() { return Set.of(); }

    @Override
    public boolean hasPermission(String userRole) {
        return "factory_super_admin".equals(userRole) || "permission_admin".equals(userRole);
    }

    @Override public boolean supportsPreview() { return false; }
    @Override public String getVersion() { return "2.0.0"; }
    @Override public Set<String> getDomainTags() { return Set.of("canvas"); }

    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
