package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesException;

import java.math.BigDecimal;
import java.util.List;

/**
 * 销售异常单 Service —— 给「被拦下的超发尝试」留痕。
 *
 * <h2>🔴 它必须是一个<b>独立的 Spring bean</b>，不能是 SalesServiceImpl 的私有方法</h2>
 * 调用它的时刻，外层 {@code warehouseConfirmDelivery} 事务<b>下一行就要抛 409</b>，
 * 注定整体回滚。要把记录留下来，写入必须跑在
 * {@code @Transactional(propagation = REQUIRES_NEW)} 的独立事务里 ——
 * 而 Spring 的 {@code @Transactional} 是<b>代理</b>生效的:
 * 同类内部的 {@code this.xxx()} 自调用不过代理，propagation 一个字都不起作用，
 * 写入会照样跟着外层一起回滚，而且<b>不报任何错</b>。
 *
 * <p>⇒ 判据不是「有没有写 REQUIRES_NEW」，是「这次调用有没有过代理」。
 */
public interface SalesExceptionService {

    /**
     * 记录一次<b>被超发上限拦下</b>的发货确认尝试。
     *
     * <h2>本方法的两条硬约束</h2>
     * <ol>
     *   <li><b>独立提交</b> —— 在 {@code REQUIRES_NEW} 里写，外层回滚不带走它。</li>
     *   <li><b>永不改变调用方的结果</b> —— 任何失败（取号撞车、库连不上、字段超长）
     *       都在实现里被吞掉并打 ERROR 日志，⛔ 绝不允许把原本的 409 变成 500。
     *       「这次发货被拒绝」是产品行为，留痕失败不该动它。</li>
     * </ol>
     *
     * @param factoryId   工厂
     * @param record      发货单（取单号 / 销售订单 id）
     * @param item        出事的发货行
     * @param actual      仓管<b>想</b>填的实发量（⛔ 不是发出去的量）
     * @param limitRate   当时生效的上限比率；{@code null} 表示不限制（正常不会走到这里）
     * @param limitSource 上限来源标记 {@code OverReceiveLimit.source()}
     * @param maxAllowed  当时算出的最大可发量
     * @param userId      操作人；可为 null
     * @return 落库的异常单；留痕失败时返回 {@code null}（⛔ 调用方不许据此改变自己的行为）
     */
    SalesException recordBlockedOverShip(String factoryId,
                                         SalesDeliveryRecord record,
                                         SalesDeliveryItem item,
                                         BigDecimal actual,
                                         BigDecimal limitRate,
                                         String limitSource,
                                         BigDecimal maxAllowed,
                                         Long userId);

    /**
     * 查工厂的异常单列表（按尝试时间倒序）。
     *
     * @param deliveryRecordId 可选，非空时只看这张发货单
     */
    List<SalesException> listExceptions(String factoryId, String deliveryRecordId);
}
