package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;

/**
 * 挡「放行」的外部条件 (缺口 13: 未处理的 CCP 偏离)。
 *
 * <p>返回挡住这一批放行的未处理偏离条数; 0 = 不挡。由 CCP 监控实现
 * ({@code CcpMonitoringService}), 质检放行只问这一个接口 —— 判定口径 (按批次号 + 生产计划) 只写一份。
 */
public interface QcReleaseBlocker {

    long openDeviationsForSemi(String factoryId, SemiFinishedInventory semi);

    long openDeviationsForWipBatch(String factoryId, MaterialBatch wipBatch);

    long openDeviationsForFinishedGoods(String factoryId, FinishedGoodsBatch batch);
}
