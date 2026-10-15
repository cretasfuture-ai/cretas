package com.cretas.aims.repository.equipmentplan;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraftEntry; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.stereotype.Repository;
@Repository public interface EquipmentCalibrationPlanDraftEntryRepository extends JpaRepository<EquipmentCalibrationPlanDraftEntry,String> { List<EquipmentCalibrationPlanDraftEntry> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId,String factoryId); }
