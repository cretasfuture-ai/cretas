package com.cretas.aims.repository.learning;

import com.cretas.aims.entity.Customer;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 训练样本「本厂名单」的原始行 (Steve 2026-10-29 选 A; 2026-11-03 Q1 + 复审 N2/N6)。
 *
 * <p>全部是原生查询, 含已软删除的行 (fail-closed): 离职员工、停用客户 / 供应商的联系人照样是本厂的人;
 * 已软删除的保密产品照样是保密产品 (JPQL / {@code findAllById} 会被实体上的 {@code @Where(deleted_at IS NULL)}
 * 过滤掉 —— 复审 N2: 以前 Java 漏了它们、Python 没漏)。与 Python {@code common.process_secret_sql} 同一口径。
 */
@Transactional(readOnly = true)
public interface FactoryNameDirectoryRepository extends Repository<Customer, String> {

    /** 本厂人员姓名 + 客户 / 供应商联系人 (人名)。 */
    @Query(value = "SELECT full_name FROM users WHERE factory_id = :factoryId AND full_name IS NOT NULL"
            + " UNION SELECT contact_name FROM customers WHERE factory_id = :factoryId AND contact_name IS NOT NULL"
            + " UNION SELECT contact_person FROM customers WHERE factory_id = :factoryId AND contact_person IS NOT NULL"
            + " UNION SELECT contact_name FROM suppliers WHERE factory_id = :factoryId AND contact_name IS NOT NULL"
            + " UNION SELECT contact_person FROM suppliers WHERE factory_id = :factoryId AND contact_person IS NOT NULL",
            nativeQuery = true)
    List<String> findPeopleNames(@Param("factoryId") String factoryId);

    @Query(value = "SELECT DISTINCT name FROM customers WHERE factory_id = :factoryId AND name IS NOT NULL",
            nativeQuery = true)
    List<String> findCustomerNames(@Param("factoryId") String factoryId);

    /** 本厂保密产品的原始字段 (含已软删除的产品): id, code, name, category, product_category。 */
    @Query(value = "SELECT CAST(id AS VARCHAR(100)), code, name, category, product_category FROM product_types"
            + " WHERE factory_id = :factoryId AND CAST(id AS VARCHAR(100)) IN (:ids)",
            nativeQuery = true)
    List<Object[]> findProtectedProductRows(@Param("factoryId") String factoryId, @Param("ids") Collection<String> ids);

    /** 本厂保密策略上登记的专属别名 (每行一个 JSON 数组; Flyway V20261104_12)。 */
    @Query(value = "SELECT p.aliases FROM process_secret_policies p"
            + " JOIN product_types pt ON CAST(pt.id AS VARCHAR(100)) = p.product_type_id AND pt.factory_id = p.factory_id"
            + " WHERE p.factory_id = :factoryId AND p.aliases IS NOT NULL",
            nativeQuery = true)
    List<String> findPolicyAliases(@Param("factoryId") String factoryId);
}
