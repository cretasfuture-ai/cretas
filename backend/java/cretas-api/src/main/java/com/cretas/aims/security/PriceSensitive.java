package com.cretas.aims.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for fields and computed getters containing price / amount /
 * cost information that must be masked from users lacking the
 * {@code procurement:price:view} permission.
 *
 * <h2>Field target ({@link ElementType#FIELD})</h2>
 * <p>Fields annotated with {@code @PriceSensitive} are stripped (set to
 * {@code null}) by {@link PriceFieldResponseAdvice} after the controller
 * returns and before Jackson serialization. The stripping is recursive — it
 * applies to nested objects, collections and {@link com.cretas.aims.dto.common.ApiResponse}
 * payloads.
 *
 * <h2>Method target ({@link ElementType#METHOD}) — added 2026-05-12 (P0 fix)</h2>
 * <p>Annotating a {@code @Transient} computed getter (e.g. {@code getPayableAmount()})
 * marks it as price-sensitive. {@link PriceFieldResponseAdvice} treats the
 * underlying field-backing data as nulled, and the getter itself <b>MUST</b>
 * include a defensive null guard at the top:
 *
 * <pre>{@code
 *   @Transient
 *   @PriceSensitive
 *   public BigDecimal getPayableAmount() {
 *       if (totalAmount == null) return null;   // ← defensive, prevents NPE
 *       BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
 *       BigDecimal tax = taxAmount != null ? taxAmount : BigDecimal.ZERO;
 *       return totalAmount.subtract(discount).add(tax);
 *   }
 * }</pre>
 *
 * <p><b>Why the defensive guard is mandatory</b>: When {@code totalAmount} is
 * stripped to {@code null} by {@link PriceFieldResponseAdvice} for a
 * {@code warehouse_manager}, Jackson will still invoke the computed getter
 * during serialization (because Jackson's {@code BeanPropertyWriter} only
 * checks property exclusion at registration time, not per-request). Without
 * the guard, {@code null.subtract(...)} throws NPE → HTTP 500.
 *
 * <p><b>Background</b>: PR #423 introduced field stripping, which exposed
 * latent NPEs in 7 {@code @Transient} computed getters that did not null-check
 * their inputs. The 2026-05-12 P0 fix added defensive guards <i>and</i> this
 * METHOD target so static analysis / future grep can find all price-derived
 * accessors at once.
 *
 * <h2>Field usage (existing)</h2>
 * <pre>{@code
 *   public class PurchaseOrderItem {
 *       @PriceSensitive
 *       private BigDecimal unitPrice;
 *
 *       @PriceSensitive
 *       private BigDecimal lineAmount;
 *   }
 * }</pre>
 *
 * <p>RBAC isolation source-of-truth: PR #415 Option B (2026-05-12), extended
 * to METHOD target in P0 hotfix (2026-05-12).
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-05-12
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PriceSensitive {

    /**
     * 采购价 / 采购金额 (S5-12, Steve 裁定「按权限配置，不按固定角色名单」):
     * 只看工厂配置的 procurement 模块读权限 (L2 → L1 → 兜底, 与登录响应 modulePermissions 同一条链),
     * 不看 {@code procurement:price:view} 的固定角色名单。
     */
    String PURCHASE = "procurement:read";

    /**
     * 采购发票金额: 采购读 <b>或</b> 财务读任一即可见 (财务在收票/三单核对页要看票面金额)。
     * 由采购单价推出来的数 (应开金额、采购单价) 仍标 {@link #PURCHASE}。
     */
    String PURCHASE_OR_FINANCE = "procurement:read|finance:read";

    /**
     * Permission spec required to view this field/method. Default is {@code procurement:price:view}
     * (固定角色名单, 现只管非采购金额). {@code |} 分隔表示任一即可; 由 {@link PriceVisibility} 统一解析。
     */
    String permission() default "procurement:price:view";
}
