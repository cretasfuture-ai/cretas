package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 原料批次质检后改成了「可用」(从质检中 / 其它可放行状态)。缺料实例据此把「质检中」挪成「已放行」, 重算缺口。
 *
 * <p>只是通知, 不带判定; 发出它的两个地方 (处置桥接 / 放行决策工具) 的质检规则不变。
 */
@Getter
public class MaterialBatchReleasedEvent extends ApplicationEvent {

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
