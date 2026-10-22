package com.cretas.aims.repository.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessSecretGrantRepository extends JpaRepository<ProcessSecretGrant, Long> {

    /** 有效授权: 同厂、同产品、同一被授权人, 未撤销且未到期。 */
    @Query("SELECT g FROM ProcessSecretGrant g WHERE g.factoryId = :factoryId AND g.productTypeId = :productTypeId "
            + "AND g.granteeType = :granteeType AND g.granteeId = :granteeId "
            + "AND g.revokedAt IS NULL AND g.expiresAt > :now")
    List<ProcessSecretGrant> findActive(@Param("factoryId") String factoryId,
                                        @Param("productTypeId") String productTypeId,
                                        @Param("granteeType") ProcessSecretGranteeType granteeType,
                                        @Param("granteeId") Long granteeId,
                                        @Param("now") LocalDateTime now);

    List<ProcessSecretGrant> findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(String factoryId, String productTypeId);

    Optional<ProcessSecretGrant> findByIdAndFactoryId(Long id, String factoryId);

    /** 策略被删除或转为 B 档时, 一次性撤销该产品全部未撤销授权。 */
    @Modifying
    @Query("UPDATE ProcessSecretGrant g SET g.revokedAt = :now, g.revokedBy = :by "
            + "WHERE g.factoryId = :factoryId AND g.productTypeId = :productTypeId AND g.revokedAt IS NULL")
    int revokeAllForProduct(@Param("factoryId") String factoryId,
                            @Param("productTypeId") String productTypeId,
                            @Param("now") LocalDateTime now,
                            @Param("by") Long by);
}
