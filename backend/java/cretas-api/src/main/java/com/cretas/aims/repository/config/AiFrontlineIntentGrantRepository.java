package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.AiFrontlineIntentGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiFrontlineIntentGrantRepository extends JpaRepository<AiFrontlineIntentGrant, Long> {

    /** 本工厂当前有效的开放记录。 */
    List<AiFrontlineIntentGrant> findByFactoryIdAndRevokedAtIsNull(String factoryId);

    Optional<AiFrontlineIntentGrant> findByFactoryIdAndRoleCodeAndIntentCodeAndRevokedAtIsNull(
            String factoryId, String roleCode, String intentCode);
}
