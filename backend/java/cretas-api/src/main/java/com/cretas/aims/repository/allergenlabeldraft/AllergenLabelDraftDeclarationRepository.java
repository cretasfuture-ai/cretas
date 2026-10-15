package com.cretas.aims.repository.allergenlabeldraft;

import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraftDeclaration;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AllergenLabelDraftDeclarationRepository
        extends JpaRepository<AllergenLabelDraftDeclaration, String> {
    List<AllergenLabelDraftDeclaration> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
