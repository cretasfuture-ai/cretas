package com.cretas.aims.repository.equipmentplan;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraft; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.stereotype.Repository;
@Repository public interface EquipmentCalibrationPlanDraftRepository extends JpaRepository<EquipmentCalibrationPlanDraft,String> { Optional<EquipmentCalibrationPlanDraft> findByFactoryIdAndIdempotencyKey(String factoryId,String idempotencyKey); Optional<EquipmentCalibrationPlanDraft> findByIdAndFactoryId(String id,String factoryId); }
