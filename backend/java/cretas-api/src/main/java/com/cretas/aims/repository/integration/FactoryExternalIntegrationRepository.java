package com.cretas.aims.repository.integration;

import com.cretas.aims.entity.integration.FactoryExternalIntegration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 外部软件接口授权 (平台专属表)。读写只经 {@code ExternalIntegrationService}。 */
public interface FactoryExternalIntegrationRepository
        extends JpaRepository<FactoryExternalIntegration, FactoryExternalIntegration.Key> {

    List<FactoryExternalIntegration> findByFactoryIdOrderBySystemAsc(String factoryId);

    Optional<FactoryExternalIntegration> findByFactoryIdAndSystem(String factoryId, String system);

    Optional<FactoryExternalIntegration> findByPushTokenSha256(String pushTokenSha256);
}
