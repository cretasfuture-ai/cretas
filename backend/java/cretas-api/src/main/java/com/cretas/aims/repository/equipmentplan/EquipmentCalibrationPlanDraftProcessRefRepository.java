package com.cretas.aims.repository.equipmentplan;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraftProcessRef; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.stereotype.Repository;
@Repository public interface EquipmentCalibrationPlanDraftProcessRefRepository extends JpaRepository<EquipmentCalibrationPlanDraftProcessRef,String> { List<EquipmentCalibrationPlanDraftProcessRef> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId,String factoryId); }
