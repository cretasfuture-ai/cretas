package com.cretas.aims.repository.blueprintapproval;

import com.cretas.aims.entity.blueprintapproval.FactoryAssemblyApproval;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FactoryAssemblyApprovalRepository extends JpaRepository<FactoryAssemblyApproval, String> {
    Optional<FactoryAssemblyApproval> findByIdAndFactoryId(String id, String factoryId);

    /**
     * Recoverable approvals of one factory's blueprint, newest first. The database isolates factory,
     * blueprint and status; the blueprint version lives in the stored plan snapshot and is judged by
     * the caller (there is no version column).
     */
    @Query("select a from FactoryAssemblyApproval a where a.factoryId = :factoryId and a.blueprintId = :blueprintId"
            + " and a.status in :statuses and a.deletedAt is null order by a.createdAt desc, a.id desc")
    List<FactoryAssemblyApproval> findRecoverable(@Param("factoryId") String factoryId,
            @Param("blueprintId") String blueprintId, @Param("statuses") Collection<String> statuses,
            org.springframework.data.domain.Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FactoryAssemblyApproval a where a.id = :id and a.factoryId = :factoryId and a.deletedAt is null")
    Optional<FactoryAssemblyApproval> lockByIdAndFactoryId(@Param("id") String id, @Param("factoryId") String factoryId);
}
