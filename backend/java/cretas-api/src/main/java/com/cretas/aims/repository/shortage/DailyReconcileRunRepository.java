package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.DailyReconcileRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyReconcileRunRepository extends JpaRepository<DailyReconcileRun, String> {

    List<DailyReconcileRun> findByFactoryIdOrderByStartedAtDesc(String factoryId, Pageable pageable);
}
