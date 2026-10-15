package com.cretas.aims.repository.roletrainingdraft;

import com.cretas.aims.entity.roletrainingdraft.RoleTrainingDraftPosition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleTrainingDraftPositionRepository
        extends JpaRepository<RoleTrainingDraftPosition, String> {
    List<RoleTrainingDraftPosition> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
