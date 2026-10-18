package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.ReturnOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface ReturnOrderItemRepository extends JpaRepository<ReturnOrderItem, Long> {

    List<ReturnOrderItem> findByReturnOrderId(String returnOrderId);

    @Query("SELECT i FROM ReturnOrderItem i JOIN i.returnOrder r WHERE r.factoryId=:factory "
            + "AND r.status=com.cretas.aims.entity.enums.ReturnOrderStatus.COMPLETED "
            + "AND r.returnType=com.cretas.aims.entity.enums.ReturnType.SALES_RETURN AND r.withGoods=true "
            + "AND (i.sourceFinishedGoodsBatchId=:source OR "
            + "(i.sourceFinishedGoodsBatchId IS NULL AND i.batchNumber=:batchNumber)) ORDER BY i.id")
    List<ReturnOrderItem> findCompletedPhysicalReturnsForSource(@Param("factory") String factory,
            @Param("source") String source, @Param("batchNumber") String batchNumber);
}
