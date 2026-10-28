package com.cretas.aims.service.inventory;

import java.util.List;

/**
 * SP11: 进销存台账期末快照服务.
 *
 * <p>在月结 (AccountingPeriod confirmClose) 时调用 {@link #createForPeriod},
 * 快照当期各物料期末结存, 作为下一期期初.
 */
public interface InventoryLedgerSnapshotService {

    /**
     * 为指定会计期创建期末快照 (幂等: 同 factoryId + accountingPeriodId + materialTypeId 唯一键 upsert).
     *
     * @param factoryId         工厂 ID
     * @param accountingPeriodId 会计期 ID
     */
    void createForPeriod(String factoryId, String accountingPeriodId);

    /**
     * Repair a CLOSED period that has no inventory snapshots. The operation is
     * idempotent: an existing snapshot set is returned as-is and is never
     * overwritten.
     */
    RepairResult repairMissingInventorySnapshots(String factoryId, String accountingPeriodId);

    record RepairResult(String factoryId, String accountingPeriodId, int created,
                        int existing, List<String> materialTypeIds) {
    }
}
