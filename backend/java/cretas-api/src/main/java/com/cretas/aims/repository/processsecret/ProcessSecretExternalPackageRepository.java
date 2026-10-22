package com.cretas.aims.repository.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretExternalPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessSecretExternalPackageRepository extends JpaRepository<ProcessSecretExternalPackage, Long> {

    Optional<ProcessSecretExternalPackage> findByFactoryIdAndPackageRefAndPackageVersion(String factoryId,
                                                                                        String packageRef,
                                                                                        String packageVersion);

    List<ProcessSecretExternalPackage> findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(String factoryId,
                                                                                         String productTypeId);
}
