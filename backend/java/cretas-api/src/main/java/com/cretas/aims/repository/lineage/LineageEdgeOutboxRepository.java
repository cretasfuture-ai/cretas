package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.LineageEdgeOutboxEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 血缘边补写台账。见 {@link LineageEdgeOutboxEntry}。 */
@Repository
public interface LineageEdgeOutboxRepository extends JpaRepository<LineageEdgeOutboxEntry, String> {

    /**
     * 到期该补写的 PENDING 行（最老的先来）。
     *
     * @param createdBefore 只取登记早于此刻的行 —— 刚登记的行由提交后的即时写入负责，
     *                      补写器不去抢（两边同时写同一条边会撞唯一索引，虽然结论正确，但读数会多一次 FAILED 噪声）
     */
    @Query("SELECT o FROM LineageEdgeOutboxEntry o WHERE o.status = 'PENDING' "
            + "AND o.nextAttemptAt <= :now AND o.createdAt < :createdBefore ORDER BY o.createdAt ASC")
    List<LineageEdgeOutboxEntry> findDue(@Param("now") LocalDateTime now,
                                         @Param("createdBefore") LocalDateTime createdBefore,
                                         Pageable page);

    long countByStatus(String status);

    long countByFactoryIdAndStatus(String factoryId, String status);

    Optional<LineageEdgeOutboxEntry> findFirstByStatusOrderByCreatedAtAsc(String status);

    Optional<LineageEdgeOutboxEntry> findFirstByFactoryIdAndStatusOrderByCreatedAtAsc(String factoryId, String status);

    /** 这些节点上还有多少条没结掉（PENDING / DEAD）的边 —— 溯源完整性标记用。 */
    @Query("SELECT COUNT(o) FROM LineageEdgeOutboxEntry o WHERE o.factoryId = :factoryId "
            + "AND o.status <> 'DONE' AND o.targetId IN :nodeIds")
    long countOpenTargeting(@Param("factoryId") String factoryId, @Param("nodeIds") Collection<String> nodeIds);
}
