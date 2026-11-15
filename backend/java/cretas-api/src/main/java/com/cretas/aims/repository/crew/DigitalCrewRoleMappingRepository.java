package com.cretas.aims.repository.crew;

import com.cretas.aims.entity.crew.DigitalCrewRoleMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DigitalCrewRoleMappingRepository extends JpaRepository<DigitalCrewRoleMapping, Long> {

    List<DigitalCrewRoleMapping> findByFactoryId(String factoryId);
}
