package com.cretas.aims.repository.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretExternalResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessSecretExternalResultRepository extends JpaRepository<ProcessSecretExternalResult, Long> {

    Optional<ProcessSecretExternalResult> findByFactoryIdAndEventId(String factoryId, String eventId);

    List<ProcessSecretExternalResult> findByFactoryIdAndPackageIdOrderByReceivedAtDesc(String factoryId, Long packageId);
}
