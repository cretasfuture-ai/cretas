package com.cretas.aims.repository.deviationcapadraft;

import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraftRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviationCapaTemplateDraftRuleRepository
        extends JpaRepository<DeviationCapaTemplateDraftRule, String> {
    List<DeviationCapaTemplateDraftRule> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
