package com.cretas.aims.repository.production;

import com.cretas.aims.entity.production.PlanStandardCostLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlanStandardCostLineRepository extends JpaRepository<PlanStandardCostLine, Long> {

    List<PlanStandardCostLine> findByFactoryIdAndPlanIdOrderByIdAsc(String factoryId, String planId);

    List<PlanStandardCostLine> findByFactoryIdAndPlanIdIn(String factoryId, Collection<String> planIds);

    boolean existsByFactoryIdAndPlanId(String factoryId, String planId);
}
