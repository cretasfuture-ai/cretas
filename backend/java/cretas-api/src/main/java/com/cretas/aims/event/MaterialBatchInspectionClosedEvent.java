package com.cretas.aims.event;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 原料批次离开「质检中」但<b>没有</b>变成 AVAILABLE (判不合格 DEFECTIVE / 报废 SCRAPPED / 仓库页改成鲜品、库存中等)。
 * 缺料分析的可用量只数 AVAILABLE, 所以改成鲜品等不算放行, 只是「质检中数量」减少。
 *
 * <p>缺料实例快照里的「质检中数量」只统计 INSPECTING 的批次: 放行那一半由 {@link MaterialBatchReleasedEvent} 通知,
 * 判不合格这一半若不通知, 实例会一直把这批算成「质检中、快放行了」, 缺口被低估 (D-S411-04 审查 M1)。
 * 与 {@link MaterialBatchReleasedEvent} 互补: 一次状态变化最多命中其中一个。
 *
 * <p>只是通知, 不带判定。⛔ 不要在别处 {@code new} 本事件 —— 一律走 {@link #publishIfLeftInspectingUnreleased};
 * 业务代码不直接调它, 走 {@code QualityDispositionInventoryService.publishMaterialStatusChange}。
 */
@Getter
public class MaterialBatchInspectionClosedEvent extends ApplicationEvent {

    /**
     * 在保存之后调: 之前是 INSPECTING、现在既不是 INSPECTING 也不是 AVAILABLE 才发。没有发布器时什么都不做
     * (与 {@link MaterialBatchReleasedEvent#publishIfBecameAvailable} 同一约定)。
     */
    public static void publishIfLeftInspectingUnreleased(ApplicationEventPublisher publisher, Object source,
                                                         MaterialBatch batch, MaterialBatchStatus before) {
        if (publisher == null || batch == null || before != MaterialBatchStatus.INSPECTING) {
            return;
        }
        MaterialBatchStatus after = batch.getStatus();
        if (after == MaterialBatchStatus.INSPECTING || after == MaterialBatchStatus.AVAILABLE) {
            return;
        }
        publisher.publishEvent(new MaterialBatchInspectionClosedEvent(
                source, batch.getFactoryId(), batch.getMaterialTypeId(), batch.getId(), after));
    }

    private final String factoryId;
    private final String materialTypeId;
    private final String materialBatchId;
    /** 离开质检中之后的状态 (DEFECTIVE / SCRAPPED / …)。 */
    private final MaterialBatchStatus newStatus;

    public MaterialBatchInspectionClosedEvent(Object source, String factoryId, String materialTypeId,
                                              String materialBatchId, MaterialBatchStatus newStatus) {
        super(source);
        this.factoryId = factoryId;
        this.materialTypeId = materialTypeId;
        this.materialBatchId = materialBatchId;
        this.newStatus = newStatus;
    }
}
