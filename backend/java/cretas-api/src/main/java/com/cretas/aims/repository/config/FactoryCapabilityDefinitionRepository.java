package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryCapabilityDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FactoryCapabilityDefinitionRepository
        extends JpaRepository<FactoryCapabilityDefinition, Long> {

    List<FactoryCapabilityDefinition> findByCatalogVersionOrderByDisplayOrderAsc(String catalogVersion);
}
