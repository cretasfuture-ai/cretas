package com.cretas.aims.repository.secrecy;

import com.cretas.aims.entity.secrecy.FactorySecrecySettingAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FactorySecrecySettingAuditRepository extends JpaRepository<FactorySecrecySettingAudit, Long> {

    List<FactorySecrecySettingAudit> findTop20ByFactoryIdOrderByChangedAtDescIdDesc(String factoryId);

    List<FactorySecrecySettingAudit> findTop20ByFactoryIdAndItemCodeOrderByChangedAtDescIdDesc(String factoryId,
                                                                                               String itemCode);
}
