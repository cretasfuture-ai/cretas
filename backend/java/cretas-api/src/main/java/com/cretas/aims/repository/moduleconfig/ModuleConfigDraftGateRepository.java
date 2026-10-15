package com.cretas.aims.repository.moduleconfig;

import com.cretas.aims.entity.moduleconfig.ModuleConfigDraftGate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModuleConfigDraftGateRepository extends JpaRepository<ModuleConfigDraftGate, String> {
    List<ModuleConfigDraftGate> findByDraftIdAndFactoryIdOrderByModuleIdAscGateOrdinalAsc(
            String draftId, String factoryId);
}
