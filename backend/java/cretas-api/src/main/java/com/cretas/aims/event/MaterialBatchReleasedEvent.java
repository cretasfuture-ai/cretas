package com.cretas.aims.event;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 原料批次质检后改成了「可用」(从质检中 / 其它可放行状态)。缺料实例据此把「质检中」挪成「已放行」, 重算缺口。
 *
 * <p>只是通知, 不带判定; 各放行入口的质检规则不变。⛔ 不要在别处 {@code new} 本事件 ——
 * 一律走 {@link #publishIfBecameAvailable}, 以免哪条放行路径又漏发 (仓库页改状态那条就漏过一次)。
 */
@Getter
public class MaterialBatchReleasedEvent extends ApplicationEvent {

    /**
     * 每条把原料批次翻成 AVAILABLE 的路径 (处置桥接 / 放行决策工具 / 仓库页改批次状态) 在保存之后调它:
     * 状态从别的值变成 AVAILABLE 才发, 原本就是 AVAILABLE 的不发。没有发布器 (测试 / 精简上下文) 时什么都不做。
     */
    public static void publishIfBecameAvailable(ApplicationEventPublisher publisher, Object source,
                                                MaterialBatch batch, MaterialBatchStatus before) {
        if (publisher == null || batch == null || before == MaterialBatchStatus.AVAILABLE
                || batch.getStatus() != MaterialBatchStatus.AVAILABLE) {
            return;
        }
        publisher.publishEvent(new MaterialBatchReleasedEvent(
                source, batch.getFactoryId(), batch.getMaterialTypeId(), batch.getId()));
    }

    private final String factoryId;
    private final String materialTypeId;
    private final String materialBatchId;

    public MaterialBatchReleasedEvent(Object source, String factoryId, String materialTypeId, String materialBatchId) {
        super(source);
        this.factoryId = factoryId;
        this.materialTypeId = materialTypeId;
        this.materialBatchId = materialBatchId;
    }
}
