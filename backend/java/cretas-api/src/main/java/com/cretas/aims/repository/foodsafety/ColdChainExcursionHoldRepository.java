package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainExcursionHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 超温告警转质检留痕 (缺口 15)。 */
@Repository
public interface ColdChainExcursionHoldRepository extends JpaRepository<ColdChainExcursionHold, Long> {

    List<ColdChainExcursionHold> findByFactoryIdAndAlertId(String factoryId, Long alertId);
}
