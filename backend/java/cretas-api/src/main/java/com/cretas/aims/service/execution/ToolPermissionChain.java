package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 「这个人能不能用这个工具」的判定链 —— 与 {@link ToolDispatchService#executeWithTool} 同一套规则。
 *
 * <p>三道角色门, 规则各自只有一份:
 * <ol>
 *   <li>一线角色写操作开放名单 —— {@link FrontlineAiWriteGrantService#denyReason};</li>
 *   <li>旧式角色串判定 (只给没声明权限码的老工具) —— 本类 {@link #legacyRoleDenied};</li>
 *   <li>中央 RBAC (权限码矩阵, 与 HTTP {@code @RequirePermission} 同源) —— {@link ToolRbacEnforcer}。</li>
 * </ol>
 *
 * <p>{@code executeWithTool} 按它自己的顺序 (一线门 → 预览/咨询页/W0 写确认 → 旧角色 → RBAC)
 * 分别调用这三道门; 不经过 {@code executeWithTool} 直接调 {@code tool.execute()} 的路径
 * (Skill 编排 {@code SkillExecutorImpl}, Skill 抢先路由的预检) 调 {@link #denyReason} 一次过完。
 * W0 写确认 / 预览 / 咨询页不在这里 —— 它们问的是「现在能不能写」, 不是「这个人能不能用」,
 * 各个路径已经各自接着 {@code WriteGuardService}。
 *
 * <p>⛔ 不要在调用方再抄一份这些判定 —— 这个类存在就是因为 Skill 路径曾经只接了 RBAC,
 * 漏了旧角色串与一线门 (2026-10-06 生产: 操作员经 Skill 跑了管理员级考勤统计)。
 */
@Component
public class ToolPermissionChain {

    private final ToolRbacEnforcer toolRbacEnforcer;
    private final FrontlineAiWriteGrantService frontlineWriteGrants;

    @Autowired
    public ToolPermissionChain(ToolRbacEnforcer toolRbacEnforcer,
                               @Lazy FrontlineAiWriteGrantService frontlineWriteGrants) {
        this.toolRbacEnforcer = toolRbacEnforcer;
        this.frontlineWriteGrants = frontlineWriteGrants;
    }

    /**
     * 旧式角色串判定: 工具声明需要权限、但没声明权限码 (权限码由 RBAC 判), 且角色串不通过。
     * 2026-07-24 起声明了 {@code getRequiredPermissions()} 的工具不走这里, 交给 RBAC。
     */
    public static boolean legacyRoleDenied(ToolExecutor tool, String userRole) {
        return tool != null
                && tool.requiresPermission()
                && tool.getRequiredPermissions().isEmpty()
                && !tool.hasPermission(userRole);
    }

    /** 旧式角色串拒绝时给用户看的话 (4 要素)。 */
    public static String legacyRoleDeniedMessage(String userRole, String actionName) {
        return String.format(
                "您当前的角色 (%s) 没有权限执行「%s」操作。可能原因: 1. 此操作需要更高的角色权限;"
                + " 2. 您所在的部门未被授权该功能; 3. 该操作涉及敏感数据需额外审批。"
                + "建议: 联系工厂管理员申请对应权限, 或改用您有权限的查询功能。",
                userRole != null ? userRole : "未知", actionName);
    }

    /**
     * 三道角色门一次过完。
     *
     * @param intent       工具所属的意图 (Skill 路径上没有, 传 null: 一线门此时按「没开放」处理写工具)
     * @param trustedContext 已绑定服务端身份的上下文 (RBAC 按其中 userId 加载用户)
     * @return 空 = 放行; 非空 = 拒绝, 值是给用户看的说明
     */
    public Optional<String> denyReason(ToolExecutor tool, String factoryId, String userRole,
                                       AIIntentConfig intent, Map<String, Object> trustedContext) {
        if (tool == null) {
            return Optional.of("无效的操作。");
        }
        if (frontlineWriteGrants == null) {
            // 生产上由构造注入保证非空; 没有它就分不清一线写操作, 按拒绝处理。
            return Optional.of("权限校验暂不可用，未执行任何操作。");
        }
        Optional<String> frontline = frontlineWriteGrants.denyReason(factoryId, userRole, tool, intent);
        if (frontline.isPresent()) {
            return frontline;
        }
        if (legacyRoleDenied(tool, userRole)) {
            String name = intent != null && intent.getIntentName() != null ? intent.getIntentName() : tool.getToolName();
            return Optional.of(legacyRoleDeniedMessage(userRole, name));
        }
        ToolRbacEnforcer.Decision rbac = toolRbacEnforcer.check(tool, trustedContext);
        if (!rbac.isAllowed()) {
            return Optional.of(rbac.getMessage());
        }
        return Optional.empty();
    }
}
