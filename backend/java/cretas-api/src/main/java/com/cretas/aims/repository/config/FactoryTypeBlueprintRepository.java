package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工厂类型蓝图数据访问层
 */
@Repository
public interface FactoryTypeBlueprintRepository extends JpaRepository<FactoryTypeBlueprint, String> {

    /**
     * 查询所有激活的蓝图
     */
    @Query("select b from FactoryTypeBlueprint b where b.factoryId is null and b.isActive = true and b.deletedAt is null")
    List<FactoryTypeBlueprint> findByIsActiveTrueAndDeletedAtIsNull();

    /**
     * 根据行业类型查询蓝图
     */
    @Query("select b from FactoryTypeBlueprint b where b.factoryId is null and b.industryType = :industryType and b.deletedAt is null")
    List<FactoryTypeBlueprint> findByIndustryTypeAndDeletedAtIsNull(@Param("industryType") String industryType);

    /**
     * 根据ID查询（排除已删除）
     */
    @Query("select b from FactoryTypeBlueprint b where b.factoryId is null and b.id = :id and b.deletedAt is null")
    Optional<FactoryTypeBlueprint> findByIdAndDeletedAtIsNull(@Param("id") String id);

    /**
     * 根据名称查询
     */
    @Query("select b from FactoryTypeBlueprint b where b.factoryId is null and b.name = :name and b.deletedAt is null")
    Optional<FactoryTypeBlueprint> findByNameAndDeletedAtIsNull(@Param("name") String name);

    Optional<FactoryTypeBlueprint> findByFactoryIdAndIsCurrentTrueAndDeletedAtIsNull(String factoryId);

    Optional<FactoryTypeBlueprint> findByFactoryIdAndIdAndDeletedAtIsNull(String factoryId, String id);

    Optional<FactoryTypeBlueprint> findByFactoryIdAndDraftIdempotencyKeyAndDeletedAtIsNull(
            String factoryId, String draftIdempotencyKey);

    List<FactoryTypeBlueprint> findByFactoryIdAndDeletedAtIsNullOrderByVersionDesc(String factoryId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from FactoryTypeBlueprint b where b.factoryId = :factoryId and b.isCurrent = true and b.deletedAt is null")
    Optional<FactoryTypeBlueprint> lockCurrentByFactoryId(@Param("factoryId") String factoryId);
}
