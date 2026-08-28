package com.cretas.aims.service.product;

import com.cretas.aims.domain.OrderUsageWhitelists;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 下架成品 SKU 前的在飞销售订单闸。
 *
 * <h3>它拦的是什么</h3>
 * <p>2026-08-28 生产实测 F006: {@code SOP-20260730-01-黄油鸡-成品800g} 在 08-12 被下过两张
 * 销售单 (¥500 + ¥1.00, 都已经到 {@code FINANCE_APPROVED}), 而这个 SKU 在 <b>08-14 被下架</b>。
 * 那两张单从此发不出货 —— 该 SKU 的 {@code finished_goods_batches} 历史批次数 = 0, 仓库无货可发,
 * 而<b>没有任何人收到通知</b>, 订单就静默变成僵尸单挂在那里。
 *
 * <p>{@link com.cretas.aims.service.impl.ProductTypeServiceImpl} 里已经有一道
 * {@code assertNotReferenced} 拦<b>硬删</b>(2026-08-01 六膳门 SO-20260709-0001 事故)。
 * 但「下架」走的是 {@code is_active = false}, 完全绕开那道闸 —— 同一个后果(单据发不出去),
 * 换一条路径就没人管。本类补的就是那条路径。
 *
 * <h3>为什么用 {@link OrderUsageWhitelists#SO_PLANNABLE}</h3>
 * <p>白名单一律取自 {@link OrderUsageWhitelists}, ⛔ 不在这里自己发明一组状态。
 * {@code SO_PLANNABLE} = CONFIRMED / PENDING_FINANCE_REVIEW / FINANCE_APPROVED / PROCESSING /
 * PARTIAL_DELIVERED, 语义是「还欠履约、生产计划还要为它排产」, 正是「下架之后会变成僵尸单」的那一批:
 * <ul>
 *   <li><b>DRAFT</b> 排除 —— 草稿还没承诺给客户, 改单即可, 不该因此堵住下架;</li>
 *   <li><b>FINANCE_REJECTED</b> 排除 —— 已被驳回, 不会再往下走;</li>
 *   <li><b>COMPLETED / CANCELLED</b> 排除 —— 已终结, 下架不影响它们。</li>
 * </ul>
 *
 * <p>⚠️ 没有选 {@code SO_IN_FLIGHT}(名字看着最贴切): 它<b>不含 PARTIAL_DELIVERED</b>,
 * 而部分发货的单子剩余那部分照样发不出去 —— 用它会漏掉一整类受害订单。
 * {@code SO_INVOICEABLE} 同样不合适: 它含 COMPLETED(已终结, 不该拦)且缺 CONFIRMED /
 * PENDING_FINANCE_REVIEW(在飞, 该拦), 两个方向都错。
 *
 * <h3>只拦一个方向</h3>
 * <p>只有 {@code isActive: true → false} 会被拦。上架(false → true)、以及本来就是 false 的
 * 重复写入一律放行 —— 否则「修正一个已下架商品的名字」也会被堵死。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRetirementGuard {

    /** 异常里最多列出几个订单号, 其余折成总数。 */
    static final int MAX_LISTED_ORDERS = 5;

    /** 前端 request.ts 按 errorCode 分支; 与硬删那道闸的 PRODUCT_TYPE_IN_USE 区分开, 因为补救动作不同。 */
    public static final String ERROR_CODE = "PRODUCT_TYPE_RETIRE_BLOCKED";

    private final SalesOrderItemRepository salesOrderItemRepository;

    /**
     * 在把 {@code isActive} 写进实体<b>之前</b>调用。
     *
     * @param product      当前(未修改的)产品实体, {@code getIsActive()} 必须还是库里的旧值
     * @param newIsActive  即将写入的新值; null 视为「不改状态」直接放行
     * @throws BusinessException 409 + {@link #ERROR_CODE}, 消息里带受影响的订单号
     */
    public void assertRetirable(ProductType product, Boolean newIsActive) {
        if (product == null) return;

        boolean currentlyActive = Boolean.TRUE.equals(product.getIsActive());
        boolean nextActive = Boolean.TRUE.equals(newIsActive);

        // 只拦 true → false。上架 / 重复写 false / 不改状态 全部放行。
        if (!currentlyActive || newIsActive == null || nextActive) return;

        List<String> orderNumbers = salesOrderItemRepository.findInFlightOrderNumbersByProduct(
                product.getFactoryId(), product.getId(), OrderUsageWhitelists.SO_PLANNABLE);

        if (orderNumbers == null || orderNumbers.isEmpty()) return;

        int total = orderNumbers.size();
        String listed = String.join("、", orderNumbers.subList(0, Math.min(MAX_LISTED_ORDERS, total)));
        String suffix = total > MAX_LISTED_ORDERS ? " 等 " + total + " 张" : "";

        log.warn("[ProductRetirementGuard] 拒绝下架 - factoryId={}, productTypeId={}, 在飞订单数={}",
                product.getFactoryId(), product.getId(), total);

        throw new BusinessException(409,
                "无法下架产品「" + product.getName() + "」: 还有 " + total
                        + " 张在飞的销售订单要用它 (" + listed + suffix + ")")
                .withCode(ERROR_CODE)
                .withHint("请先到「销售订单」把这些单发完或取消, 再下架该产品; "
                        + "下架后这些单无货可发, 也不会有任何提醒")
                .withHintTarget("/sales/orders")
                .withSeverity("BLOCKING");
    }
}
