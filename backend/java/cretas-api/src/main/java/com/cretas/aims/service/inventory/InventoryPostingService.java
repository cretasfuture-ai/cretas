package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.SupplierDeliveryNote;

/**
 * 供应商送货单到库存/采购底座的过账服务.
 */
public interface InventoryPostingService {

    /**
     * 供应商送货单验收入库：创建采购收货记录并确认，生成 MaterialBatch.
     */
    SupplierDeliveryNote postSupplierDeliveryToInventory(String factoryId, String noteId, Long userId);


    /**
     * 过账失败状态独立事务持久化，避免主事务回滚后丢失失败原因.
     */
    void markSupplierDeliveryPostingFailed(String factoryId, String noteId, String errorMessage);

}
