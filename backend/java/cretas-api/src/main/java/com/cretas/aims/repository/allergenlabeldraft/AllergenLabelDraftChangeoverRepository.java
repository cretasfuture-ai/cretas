package com.cretas.aims.repository.allergenlabeldraft;

import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraftChangeover;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AllergenLabelDraftChangeoverRepository
        extends JpaRepository<AllergenLabelDraftChangeover, String> {
    List<AllergenLabelDraftChangeover> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
