package com.cretas.aims.repository.crew;

import com.cretas.aims.entity.crew.DigitalCrewSettingLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DigitalCrewSettingLogRepository extends JpaRepository<DigitalCrewSettingLog, Long> {

    List<DigitalCrewSettingLog> findByFactoryIdOrderByCreatedAtDescIdDesc(String factoryId, Pageable pageable);
}
