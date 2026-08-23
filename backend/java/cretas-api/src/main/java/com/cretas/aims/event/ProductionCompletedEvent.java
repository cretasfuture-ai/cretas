package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产计划下所有批次都完工 → 计划整体完成。
 *
 * <h3>🔴 为什么这个事件必须带 {@code completedBy}（2026-08-19）</h3>
 *
 * <p>它原来<b>不带任何操作人</b>。而它的两个监听器都要给自己做的事标一个 actor，
 * 于是<b>各自发明了一个</b> {@code private static final SYSTEM_USER_ID = 0L}：
 * {@link com.cretas.aims.service.orchestration.ReverseTransferService} 拿它当调拨申请人，
 * {@link com.cretas.aims.service.orchestration.ProductionPlanTransferLifecycleListener}
 * 拿它当关单人。
 *
 * <p>prod 实测（2026-08-19，只读）：{@code users} 表 79 行，{@code min(id) = 1309}，
 * {@code id <= 10} 的行数是 <b>0</b> —— 那个 0 从来不指向任何人。
 * 全库 147 个 actor 列扫下来只有两处脏值，其中
 * {@code internal_transfers.requested_by = 0} 落了 <b>2 行</b>
 * （{@code TRF-20260816-5192} / {@code TRF-20260817-9377}，F006，D1 自动反向调拨）。
 * 表现是调拨详情页「申请人」<b>空白</b>：拿 0 去 {@code users} 查不到人。
 *
 * <p>⚠️ 它不炸 —— 那两个下游列都<b>可空且没有外键</b>，所以这不是一次违约，
 * 是一条<b>悬空引用</b>，安静地躺了九个月。
 *
 * <p>⛔ 修法不是「换一个更好的写死 id」：写死的 user id 有保质期，
 * 租户清理 / 账号停用 / 建库重来都会让它失效，而失效方式是异步监听器里的静默错误。
 * 事件源头（{@code ProductionBatch}）本来就知道是谁，把它带下来即可。
 *
 * <p><b>{@code completedBy} 允许为 null，且 null 必须原样传下去。</b>
 * 下游两列都可空，null 是合法值；⛔ 不许在任何一层把它兜底成 0 或别的常量
 * —— 那会把「不知道是谁」写成「就是那个人」。
 *
 * @see com.cretas.aims.service.orchestration.ProductionCompletedActorPropagationTest
 */
@Getter
public class ProductionCompletedEvent extends ApplicationEvent {
    private final String factoryId;
    private final String planId;
    private final String planNumber;
    private final String productTypeId;
    private final BigDecimal actualQuantity;
    private final LocalDateTime createdAt;

    /**
     * 让这个计划完工的那个人：生产负责人，没有则退到批次建单人，都没有则为 {@code null}。
     *
     * <p>⛔ 消费方拿到 null 时<b>如实往下传 null</b>，不要兜底成 0L。
     */
    private final Long completedBy;

    public ProductionCompletedEvent(Object source, String factoryId, String planId,
                                      String planNumber, String productTypeId,
                                      BigDecimal actualQuantity, Long completedBy) {
        super(source);
        this.factoryId = factoryId;
        this.planId = planId;
        this.planNumber = planNumber;
        this.productTypeId = productTypeId;
        this.actualQuantity = actualQuantity;
        this.completedBy = completedBy;
        this.createdAt = LocalDateTime.now();
    }
}
