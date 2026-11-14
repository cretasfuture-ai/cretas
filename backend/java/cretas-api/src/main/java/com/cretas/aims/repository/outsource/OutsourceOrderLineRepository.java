package com.cretas.aims.repository.outsource;

import com.cretas.aims.entity.outsource.OutsourceOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutsourceOrderLineRepository extends JpaRepository<OutsourceOrderLine, String> {

    List<OutsourceOrderLine> findByOrderIdOrderByLineNoAsc(String orderId);

    List<OutsourceOrderLine> findByOrderIdInOrderByLineNoAsc(Collection<String> orderIds);

    Optional<OutsourceOrderLine> findByIdAndFactoryId(String id, String factoryId);

    List<OutsourceOrderLine> findByFactoryIdAndProductionPlanIdIn(String factoryId, Collection<String> planIds);

    List<OutsourceOrderLine> findByFactoryIdAndWorkProcessTaskIdIn(String factoryId, Collection<Long> taskIds);
}
