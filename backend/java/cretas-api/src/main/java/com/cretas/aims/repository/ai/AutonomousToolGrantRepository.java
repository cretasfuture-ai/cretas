package com.cretas.aims.repository.ai;

import com.cretas.aims.entity.ai.AutonomousToolGrant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutonomousToolGrantRepository extends JpaRepository<AutonomousToolGrant, String> {

    /**
     * Every grant (revoked or not) bound to exactly this source, tool, run-as user and parameter
     * digest, locked for the consumption decision so concurrent runs are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AutonomousToolGrant g where g.factoryId = :factoryId and g.sourceType = :sourceType"
            + " and g.sourceRef = :sourceRef and g.toolName = :toolName and g.runAsUserId = :runAsUserId"
            + " and g.parametersDigest = :parametersDigest order by g.grantedAt asc, g.id asc")
    List<AutonomousToolGrant> lockBoundGrants(@Param("factoryId") String factoryId,
            @Param("sourceType") String sourceType, @Param("sourceRef") String sourceRef,
            @Param("toolName") String toolName, @Param("runAsUserId") Long runAsUserId,
            @Param("parametersDigest") String parametersDigest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AutonomousToolGrant g where g.id = :id and g.factoryId = :factoryId")
    Optional<AutonomousToolGrant> lockByIdAndFactoryId(@Param("id") String id, @Param("factoryId") String factoryId);

    Optional<AutonomousToolGrant> findByIdAndFactoryId(String id, String factoryId);

    List<AutonomousToolGrant> findByFactoryIdOrderByGrantedAtDesc(String factoryId);
}
