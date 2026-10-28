package com.cretas.aims.repository;

import com.cretas.aims.entity.conversation.FactoryConfirmedFact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FactoryConfirmedFactRepository extends JpaRepository<FactoryConfirmedFact, String> {

    Optional<FactoryConfirmedFact> findByFactoryIdAndFieldKeyAndStatus(
            String factoryId, String fieldKey, String status);

    List<FactoryConfirmedFact> findByFactoryIdAndStatus(String factoryId, String status);

    Optional<FactoryConfirmedFact> findByIdAndFactoryId(String id, String factoryId);

    List<FactoryConfirmedFact> findByFactoryIdOrderByFieldKeyAscVersionNoDescCreatedAtDesc(String factoryId);

    Optional<FactoryConfirmedFact> findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(
            String factoryId, String fieldKey);
}
