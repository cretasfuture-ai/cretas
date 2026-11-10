package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.service.datacenter.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 审批规则改动留痕 (#1580 独立审查低危 5): 「允许本人审批」开关与改价审批阈值改了, 记一条
 * 操作日志 (谁 / 什么时候 / 改前 → 改后)。谁来自 {@link OperationLogService#recordDiff} 取的当前请求身份。
 *
 * <p>通用请求审计 (RequestAuditInterceptor) 已经记了「谁在什么时候 PUT 了 settings」, 但不记改了什么;
 * 这一条补上开关本身的前后值。两条写入口 (工厂设置 PUT /settings 与配置中心 PUT canvas-factory-config/settings)
 * 都调本类。记录失败不影响保存 (OperationLogService 自己吞错并 log.error)。
 */
@Slf4j
@Component
public class ApprovalPolicyAudit {

    public static final String MODULE = "APPROVAL_POLICY";

    @Nullable
    private final OperationLogService operationLogService;

    public ApprovalPolicyAudit(@Nullable OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /** 改前快照 (保存之前调用)。 */
    public static Map<String, Object> snapshot(FactorySettings settings) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowSelfApproval", Boolean.TRUE.equals(settings.getAllowSelfApproval()));
        out.put("priceDecreaseApprovalPct", plain(settings.getPriceDecreaseApprovalPct()));
        out.put("priceIncreaseApprovalPct", plain(settings.getPriceIncreaseApprovalPct()));
        return out;
    }

    /** 有变化才记; 返回是否记了 (给测试用)。 */
    public boolean recordIfChanged(String factoryId, Map<String, Object> before, FactorySettings after) {
        Map<String, Object> now = snapshot(after);
        if (Objects.equals(before, now)) {
            return false;
        }
        String summary = Objects.equals(before.get("allowSelfApproval"), now.get("allowSelfApproval"))
                ? "改价审批阈值变更"
                : (Boolean.TRUE.equals(now.get("allowSelfApproval")) ? "打开「允许本人审批」" : "关闭「允许本人审批」");
        log.warn("审批规则变更: factoryId={}, {} → {}", factoryId, before, now);
        if (operationLogService != null) {
            operationLogService.recordDiff(factoryId, MODULE, "FactorySettings", factoryId, before, now, summary);
        }
        return true;
    }

    private static String plain(@Nullable BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
