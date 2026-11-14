package com.cretas.aims.repository.intent;

import com.cretas.aims.entity.Customer;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 意图飞轮脱敏用的本厂主数据名单 (INTENT-DISTILL-20261111, Steve 2026-11-14: 识别只靠本厂客户 / 产品 / 供应商表
 * 确定性匹配, ⛔ 不用语义 / embedding 猜)。
 *
 * <p>原生查询, 含已软删除的行 (fail-closed, 同 {@code FactoryNameDirectoryRepository} 的口径): 停用客户、下架产品的名字
 * 照样是本厂的名字。人员姓名复用 {@code FactoryNameDirectoryRepository#findPeopleNames}。</p>
 */
@Transactional(readOnly = true)
public interface FlywheelMasterDataRepository extends Repository<Customer, String> {

    @Query(value = "SELECT DISTINCT name FROM customers WHERE factory_id = :factoryId AND name IS NOT NULL",
            nativeQuery = true)
    List<String> findCustomerNames(@Param("factoryId") String factoryId);

    @Query(value = "SELECT name FROM suppliers WHERE factory_id = :factoryId AND name IS NOT NULL"
            + " UNION SELECT short_name FROM suppliers WHERE factory_id = :factoryId AND short_name IS NOT NULL",
            nativeQuery = true)
    List<String> findSupplierNames(@Param("factoryId") String factoryId);

    @Query(value = "SELECT DISTINCT name FROM product_types WHERE factory_id = :factoryId AND name IS NOT NULL",
            nativeQuery = true)
    List<String> findProductNames(@Param("factoryId") String factoryId);
}
