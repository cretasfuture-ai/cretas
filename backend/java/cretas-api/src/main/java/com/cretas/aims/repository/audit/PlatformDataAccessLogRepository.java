package com.cretas.aims.repository.audit;

import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlatformDataAccessLogRepository extends JpaRepository<PlatformDataAccessLog, Long> {

    /** One factory's rows, newest first (id breaks ties within the same timestamp). */
    Page<PlatformDataAccessLog> findByTargetFactoryIdOrderByCreatedAtDescIdDesc(String targetFactoryId, Pageable pageable);
}
