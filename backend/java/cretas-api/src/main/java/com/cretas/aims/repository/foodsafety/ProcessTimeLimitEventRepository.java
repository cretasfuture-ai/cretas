package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/** 超时被拦留痕与处置 (缺口 14)。 */
@Repository
public interface ProcessTimeLimitEventRepository extends JpaRepository<ProcessTimeLimitEvent, Long> {

    List<ProcessTimeLimitEvent> findByFactoryIdAndTargetTypeAndTargetIdAndStatus(
            String factoryId, String targetType, String targetId, String status);

    List<ProcessTimeLimitEvent> findByFactoryIdAndStatusOrderByBlockedAtDesc(String factoryId, String status);

    /** 处置记录页: 本厂最近 200 条。 */
    List<ProcessTimeLimitEvent> findTop200ByFactoryIdOrderByBlockedAtDesc(String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ProcessTimeLimitEvent e WHERE e.id = :id AND e.factoryId = :factoryId")
    Optional<ProcessTimeLimitEvent> findByIdAndFactoryIdForUpdate(@Param("id") Long id,
                                                                  @Param("factoryId") String factoryId);
}
