package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;

import java.util.Objects;
import java.util.Optional;

/**
 * 工具执行前、与角色无关的两道准入门: 供应商比价身份门 + 工厂功能开关。
 *
 * <p>执行期 ({@link ToolDispatchService} executeWithToolInScope) 与澄清候选
 * ({@link ClarificationCandidateService}) 调的是这同一个方法 —— 候选里出现的按钮,
 * 点了不许被这两道门拒掉 (#1747 遗留 M1 / L1; INTENT-DISTILL-20261111 第③步)。
 * 顺序与执行期原来一致: 先身份门, 再功能开关。
 */
public final class ToolExecutionAdmission {

    /** 执行期返回的状态码 (ToolDispatchService 据此构造回复; 候选据此不出按钮)。 */
    public enum Refusal {
        /** 供应商比价: 不是绑定本厂的工厂用户, 或工具/意图不是正式那一对。执行期回 FORBIDDEN。 */
        SUPPLIER_IDENTITY,
        /** 工厂管理员在功能配置里关掉了这个工具 (或本厂没开通)。执行期回 TOOL_DISABLED。 */
        TOOL_DISABLED
    }

    private ToolExecutionAdmission() {
    }

    /**
     * @return 空 = 两道门都过; 否则第一道拒绝的门。
     */
    public static Optional<Refusal> refusal(ToolRegistry toolRegistry, ToolExecutor tool, AIIntentConfig intent,
                                            String factoryId, Long userId) {
        if (supplierIdentityRefused(tool, intent, factoryId, userId)) {
            return Optional.of(Refusal.SUPPLIER_IDENTITY);
        }
        // Round 8-β Fix: Canvas factory_tool_configs 的开关原来只写不读, 执行期在这里读。
        if (toolRegistry != null && !toolRegistry.isToolEnabledForFactory(factoryId, tool.getToolName())) {
            return Optional.of(Refusal.TOOL_DISABLED);
        }
        return Optional.empty();
    }

    /**
     * 供应商比价只给绑定本厂的工厂用户, 且工具与意图必须是正式的那一对 (缓存结果同样受此约束)。
     * 平台管理员替某厂对话时没有工厂用户身份 ⇒ 拒。
     */
    private static boolean supplierIdentityRefused(ToolExecutor tool, AIIntentConfig intent,
                                                   String factoryId, Long userId) {
        boolean supplierTool = SupplierComparisonReadIntent.TOOL.equals(tool.getToolName());
        boolean supplierIntent = intent != null && SupplierComparisonReadIntent.CODE.equals(intent.getIntentCode());
        if (!supplierTool && !supplierIntent) {
            return false;
        }
        if (!supplierTool || !supplierIntent) {
            return true;
        }
        return AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == AuthenticatedActorType.FACTORY_USER)
                .filter(actor -> Objects.equals(actor.id(), userId))
                .filter(actor -> Objects.equals(actor.factoryId(), factoryId))
                .isEmpty();
    }
}
