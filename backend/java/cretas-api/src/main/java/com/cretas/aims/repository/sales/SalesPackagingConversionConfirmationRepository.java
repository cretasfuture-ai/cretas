package com.cretas.aims.repository.sales;

import com.cretas.aims.entity.sales.SalesPackagingConversionConfirmation;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 规格换算确认留痕 (Q10)。⛔ 故意继承 {@link Repository} 而不是 JpaRepository: 只有插入和查询, 没有删除 / 批量写入口。
 */
@org.springframework.stereotype.Repository
public interface SalesPackagingConversionConfirmationRepository
        extends Repository<SalesPackagingConversionConfirmation, Long> {

    SalesPackagingConversionConfirmation save(SalesPackagingConversionConfirmation confirmation);

    List<SalesPackagingConversionConfirmation> findByFactoryIdAndDeliveryItemIdOrderByIdAsc(
            String factoryId, String deliveryItemId);

    long countByFactoryIdAndDeliveryItemId(String factoryId, String deliveryItemId);
}
