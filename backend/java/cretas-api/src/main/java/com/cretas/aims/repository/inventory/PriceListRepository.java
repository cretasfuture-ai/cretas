package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PriceList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PriceListRepository extends JpaRepository<PriceList, String> {

    Optional<PriceList> findByIdAndFactoryId(String id, String factoryId);

    Page<PriceList> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    /** 某客户在某类型下的全部价目表 (含已过期/停用, 供客户档案价格页维护用)。 */
    List<PriceList> findByFactoryIdAndCustomerIdAndPriceTypeOrderByCreatedAtAsc(
            String factoryId, String customerId, String priceType);

    /** 查询当前生效的价格表 */
    @Query("SELECT p FROM PriceList p WHERE p.factoryId = :factoryId " +
            "AND p.isActive = true AND p.effectiveFrom <= :date " +
            "AND (p.effectiveTo IS NULL OR p.effectiveTo >= :date)")
    List<PriceList> findEffective(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    List<PriceList> findByFactoryIdAndPriceType(String factoryId, String priceType);

    /** 查询客户专属的当前生效价格表 */
    @Query("SELECT p FROM PriceList p WHERE p.factoryId = :factoryId " +
            "AND p.customerId = :customerId AND p.priceType = :priceType " +
            "AND p.isActive = true AND p.effectiveFrom <= :date " +
            "AND (p.effectiveTo IS NULL OR p.effectiveTo >= :date) " +
            "ORDER BY p.createdAt ASC, p.id ASC")
    List<PriceList> findEffectiveForCustomer(@Param("factoryId") String factoryId,
                                              @Param("customerId") String customerId,
                                              @Param("priceType") String priceType,
                                              @Param("date") LocalDate date);

    /** 查询全局（无客户绑定）的当前生效价格表 */
    @Query("SELECT p FROM PriceList p WHERE p.factoryId = :factoryId " +
            "AND p.customerId IS NULL AND p.priceType = :priceType " +
            "AND p.isActive = true AND p.effectiveFrom <= :date " +
            "AND (p.effectiveTo IS NULL OR p.effectiveTo >= :date)")
    List<PriceList> findEffectiveGlobal(@Param("factoryId") String factoryId,
                                         @Param("priceType") String priceType,
                                         @Param("date") LocalDate date);
}
