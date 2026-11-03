package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainEquipmentLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 冷链设备 ↔ 仓库生效时段 (缺口 15)。 */
@Repository
public interface ColdChainEquipmentLocationRepository extends JpaRepository<ColdChainEquipmentLocation, Long> {

    List<ColdChainEquipmentLocation> findByFactoryIdAndEquipmentIdOrderByEffectiveFromAsc(
            String factoryId, Long equipmentId);

    List<ColdChainEquipmentLocation> findByFactoryIdOrderByEquipmentIdAscEffectiveFromAsc(String factoryId);

    Optional<ColdChainEquipmentLocation> findFirstByFactoryIdAndEquipmentIdAndEffectiveToIsNull(
            String factoryId, Long equipmentId);
}
