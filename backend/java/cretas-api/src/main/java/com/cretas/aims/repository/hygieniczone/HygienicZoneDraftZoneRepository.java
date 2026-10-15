package com.cretas.aims.repository.hygieniczone;

import com.cretas.aims.entity.hygieniczone.HygienicZoneDraftZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HygienicZoneDraftZoneRepository extends JpaRepository<HygienicZoneDraftZone, String> {
    List<HygienicZoneDraftZone> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
