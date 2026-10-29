package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 价格可见性的唯一判定点 (S5-12)。
 *
 * <p>规格 = {@link PriceSensitive#permission()} / {@link PriceScope#value()} 的取值:
 * 一个或多个权限码, {@code |} 分隔, 任一满足即可见。procurement:read / finance:read 只看配置
 * (L2 工厂覆盖 → L1 平台默认; 都没有行时只认采购 / 财务岗, 不落硬编码兜底矩阵),
 * 在权限配置界面里改得动; 其它权限码照常 {@link PermissionService#hasPermission}。
 *
 * <p>判定出错一律按不可见处理 (fail closed)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceVisibility {

    private final PermissionService permissionService;

    /**
     * 工厂没有 L2 / L1 配置行时谁能看采购价 (S5-12 裁定 b): 只有采购岗; 财务经由
     * {@link PriceSensitive#PURCHASE_OR_FINANCE} 的 finance:read 看财务入口上的采购金额。
     * ⛔ 不要加 warehouse_manager / viewer / operations_coordinator / dispatcher / production_manager —
     * PR #415 有意不让他们看采购价, 兜底矩阵虽然给了他们采购读。
     */
    static final java.util.Set<String> PROCUREMENT_FALLBACK_ROLES = java.util.Set.of("procurement_manager");

    /** 同上, finance:read 未配置时只有财务岗。 */
    static final java.util.Set<String> FINANCE_FALLBACK_ROLES = java.util.Set.of("finance_manager");

    /** 采购 / 财务读走「只看配置」的判定, 其它权限码照常 hasPermission。 */
    private boolean check(User user, String code) {
        if ("procurement:read".equals(code)) {
            return permissionService.hasConfiguredPermission(user, code, PROCUREMENT_FALLBACK_ROLES);
        }
        if ("finance:read".equals(code)) {
            return permissionService.hasConfiguredPermission(user, code, FINANCE_FALLBACK_ROLES);
        }
        return permissionService.hasPermission(user, code);
    }

    public boolean canView(User user, String spec) {
        if (user == null || spec == null || spec.isBlank()) {
            return false;
        }
        for (String code : spec.split("\\|")) {
            String trimmed = code.trim();
            if (trimmed.isEmpty()) continue;
            try {
                if (check(user, trimmed)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("PriceVisibility: permission check failed userId={} code={}, masking: {}",
                        user.getId(), trimmed, e.getMessage());
            }
        }
        return false;
    }

    /** 采购价 / 采购金额是否可见: 只看配置的 procurement 读权限。 */
    public boolean canViewPurchasePrices(User user) {
        return canView(user, PriceSensitive.PURCHASE);
    }
}
