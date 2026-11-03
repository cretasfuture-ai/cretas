package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 9 P2.D {@link ColdChainEquipment}.
 *
 * <p>BaseEntity 软删除 via @Where 自动过滤 deleted_at IS NULL.
 */
@Repository
public interface ColdChainEquipmentRepository extends JpaRepository<ColdChainEquipment, Long> {

    /** 按设备编码 lookup (factory 内唯一). */
    Optional<ColdChainEquipment> findByFactoryIdAndEquipmentCode(
            String factoryId, String equipmentCode);

    /** 列出 factory 全部启用设备 (scheduler 定时扫描). */
    List<ColdChainEquipment> findByFactoryIdAndActiveTrue(String factoryId);

    /** 列出全部启用设备 (跨 factory scheduler 用). */
    List<ColdChainEquipment> findByActiveTrue();

    /** 唯一性 check (R4 幂等). */
    boolean existsByFactoryIdAndEquipmentCode(String factoryId, String equipmentCode);

    /** 缺口 15: 换库位时锁住设备行, 两个并发的「换库」不会各开一条新时段。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "SELECT e FROM ColdChainEquipment e WHERE e.id = :id AND e.factoryId = :factoryId")
    Optional<ColdChainEquipment> findByIdAndFactoryIdForUpdate(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("factoryId") String factoryId);

    Optional<ColdChainEquipment> findByIdAndFactoryId(Long id, String factoryId);

    List<ColdChainEquipment> findByFactoryIdOrderByEquipmentCodeAsc(String factoryId);
}
