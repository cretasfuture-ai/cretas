package com.cretas.aims.repository.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessSecretPolicyRepository extends JpaRepository<ProcessSecretPolicy, Long> {

    Optional<ProcessSecretPolicy> findByFactoryIdAndProductTypeId(String factoryId, String productTypeId);

    List<ProcessSecretPolicy> findByFactoryIdOrderByProductTypeIdAsc(String factoryId);

    boolean existsByFactoryId(String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProcessSecretPolicy p WHERE p.factoryId = :factoryId AND p.productTypeId = :productTypeId")
    Optional<ProcessSecretPolicy> findForUpdate(@Param("factoryId") String factoryId,
                                                @Param("productTypeId") String productTypeId);
}
