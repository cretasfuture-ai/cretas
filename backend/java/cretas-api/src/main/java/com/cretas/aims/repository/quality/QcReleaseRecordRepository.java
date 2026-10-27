package com.cretas.aims.repository.quality;

import com.cretas.aims.entity.quality.QcReleaseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 质检放行记录 (缺口 11)。只增不改; 读按对象或按工厂时间倒序。 */
@Repository
public interface QcReleaseRecordRepository extends JpaRepository<QcReleaseRecord, Long> {

    @Query("SELECT r FROM QcReleaseRecord r WHERE r.factoryId = :factoryId " +
            "AND r.targetType = :targetType AND r.targetId = :targetId ORDER BY r.releasedAt DESC")
    List<QcReleaseRecord> findByTarget(@Param("factoryId") String factoryId,
                                       @Param("targetType") String targetType,
                                       @Param("targetId") String targetId);

    /** 最近的放行记录 (页面「放行记录」页签)。 */
    List<QcReleaseRecord> findTop200ByFactoryIdOrderByReleasedAtDesc(String factoryId);
}
