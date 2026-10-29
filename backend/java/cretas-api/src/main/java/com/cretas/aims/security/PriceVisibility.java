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
 * 一个或多个权限码, {@code |} 分隔, 任一满足即可见。每个权限码都交给
 * {@link PermissionService#hasPermission}, 采购价因此走工厂配置的模块读权限 (L2 → L1 → 兜底),
 * 在权限配置界面里改得动。
 *
 * <p>判定出错一律按不可见处理 (fail closed)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceVisibility {

    private final PermissionService permissionService;

    public boolean canView(User user, String spec) {
        if (user == null || spec == null || spec.isBlank()) {
            return false;
        }
        for (String code : spec.split("\\|")) {
            String trimmed = code.trim();
            if (trimmed.isEmpty()) continue;
            try {
                if (permissionService.hasPermission(user, trimmed)) {
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
