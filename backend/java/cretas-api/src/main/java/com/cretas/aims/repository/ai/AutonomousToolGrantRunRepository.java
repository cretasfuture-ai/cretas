package com.cretas.aims.repository.ai;

import com.cretas.aims.entity.ai.AutonomousToolGrantRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutonomousToolGrantRunRepository extends JpaRepository<AutonomousToolGrantRun, String> {

    Optional<AutonomousToolGrantRun> findByGrantIdAndRunKey(String grantId, String runKey);

    long countByGrantId(String grantId);
}
