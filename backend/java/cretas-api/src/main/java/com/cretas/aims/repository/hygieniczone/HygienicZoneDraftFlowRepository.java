package com.cretas.aims.repository.hygieniczone;

import com.cretas.aims.entity.hygieniczone.HygienicZoneDraftFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HygienicZoneDraftFlowRepository extends JpaRepository<HygienicZoneDraftFlow, String> {
    List<HygienicZoneDraftFlow> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
