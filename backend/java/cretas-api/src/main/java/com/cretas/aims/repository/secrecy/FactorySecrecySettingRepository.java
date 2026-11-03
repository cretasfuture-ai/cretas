package com.cretas.aims.repository.secrecy;

import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FactorySecrecySettingRepository
        extends JpaRepository<FactorySecrecySetting, FactorySecrecySetting.Key> {

    List<FactorySecrecySetting> findByFactoryId(String factoryId);
}
