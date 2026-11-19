package com.cretas.aims.service.crew;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.execution.ToolPermissionChain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 数字班组「谁能确认这件事」= 意图上登记的权限码 <b>且</b> 执行时真生效的那几道门 (#1816 终审 M1)。
 *
 * <p>意图码登记的 required_permission 只是第一道; 执行确认时工具还要过 {@link ToolPermissionChain#denyReason}
 * (一线开放名单 / 旧角色串 / ToolRbacEnforcer 的工具权限表与 getRequiredRoles)。只看登记码会把事项推给
 * 执行时必被拒的人 (例如有 quality:read_write 但不在质检工具角色名单里的人)。这里按确认人身份把同一条链预跑一遍,
 * 不另抄判定 —— 与执行同一个 {@link ToolPermissionChain}。
 *
 * <p>意图没绑工具 (Skill / 问答) 时只靠登记码; 意图配置读不到、工具没注册 ⇒ 拒 (fail closed)。
 */
@Component
public class DigitalCrewExecutionGate {

    private final AIIntentService intentConfigs;
    private final ToolRegistry toolRegistry;
    private final ToolPermissionChain chain;

    @Autowired
    public DigitalCrewExecutionGate(@Lazy AIIntentService intentConfigs, @Lazy ToolRegistry toolRegistry,
                                    @Lazy ToolPermissionChain chain) {
        this.intentConfigs = intentConfigs;
        this.toolRegistry = toolRegistry;
        this.chain = chain;
    }

    /** 空 = 这个人执行确认时过得了工具这几道门; 非空 = 会被拒, 值是原因。 */
    public Optional<String> denyReason(String factoryId, User user, String intentCode) {
        if (user == null || intentCode == null) return Optional.of("没有确认人或意图，不能确认");
        AIIntentConfig intent;
        try {
            intent = intentConfigs.getIntentByCode(factoryId, intentCode).orElse(null);
        } catch (RuntimeException e) {
            return Optional.of("意图配置暂时读不到，不能确认");
        }
        if (intent == null) return Optional.of("这件事对应的意图已不存在，不能确认");
        String toolName = intent.getToolName();
        if (toolName == null || toolName.isBlank()) return Optional.empty();
        ToolExecutor tool = toolRegistry.getExecutor(toolName).orElse(null);
        if (tool == null) return Optional.of("这件事对应的工具没有注册，不能确认");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("userId", user.getId());
        ctx.put("factoryId", factoryId);
        ctx.put("userRole", user.getRoleCode());
        return chain.denyReason(tool, factoryId, user.getRoleCode(), intent, ctx);
    }
}
