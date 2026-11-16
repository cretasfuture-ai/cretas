package com.cretas.aims.repository.crew;

import com.cretas.aims.entity.crew.DigitalCrewMemberSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DigitalCrewMemberSettingRepository extends JpaRepository<DigitalCrewMemberSetting, Long> {

    List<DigitalCrewMemberSetting> findByFactoryId(String factoryId);
}
