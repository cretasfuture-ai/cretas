package com.cretas.aims.repository.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAccessEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProcessSecretAccessEventRepository extends JpaRepository<ProcessSecretAccessEvent, Long> {

    List<ProcessSecretAccessEvent> findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(String factoryId,
                                                                                     String productTypeId,
                                                                                     Pageable pageable);
}
