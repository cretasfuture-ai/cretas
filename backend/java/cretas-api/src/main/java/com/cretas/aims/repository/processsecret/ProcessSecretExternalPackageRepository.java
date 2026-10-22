package com.cretas.aims.repository.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretExternalPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** 产品离开「客户侧保存」时停用全部工艺包, 改回来不会让旧包自动复活 (复审 M-4)。 */
    @Modifying
    @Query("UPDATE ProcessSecretExternalPackage p SET p.status = com.cretas.aims.entity.processsecret."
            + "ProcessSecretPackageStatus.RETIRED WHERE p.factoryId = :factoryId AND p.productTypeId = :productTypeId "
            + "AND p.status = com.cretas.aims.entity.processsecret.ProcessSecretPackageStatus.ACTIVE")
    int retireAllForProduct(@Param("factoryId") String factoryId, @Param("productTypeId") String productTypeId);
}
