package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.ProcessTimeMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 时限计时的人工登记 (缺口 14)。 */
@Repository
public interface ProcessTimeMarkRepository extends JpaRepository<ProcessTimeMark, Long> {

    @Query("SELECT m FROM ProcessTimeMark m WHERE m.factoryId = :factoryId AND m.targetType = :targetType "
            + "AND m.targetId IN :targetIds ORDER BY m.startedAt ASC")
    List<ProcessTimeMark> findByTargets(@Param("factoryId") String factoryId,
                                        @Param("targetType") String targetType,
                                        @Param("targetIds") Collection<String> targetIds);

    Optional<ProcessTimeMark> findByIdAndFactoryId(Long id, String factoryId);
}
