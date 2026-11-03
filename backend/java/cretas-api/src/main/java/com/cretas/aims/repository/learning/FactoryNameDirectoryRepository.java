package com.cretas.aims.repository.learning;

import com.cretas.aims.entity.Customer;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 训练样本「本厂名单」里的人员姓名与客户名称 (Steve 2026-10-29 选 A: 人名 / 客户名按本厂表精确匹配)。
 *
 * <p>原生查询, 含已软删除的行 —— 离职员工、停用客户的名字照样是本厂的人和客户 (JPQL 会被实体上的
 * {@code @Where(deleted_at IS NULL)} 过滤掉)。与 Python {@code common.process_secret_sql.FACTORY_DIRECTORY_SQL}
 * 同一口径。
 */
@Transactional(readOnly = true)
public interface FactoryNameDirectoryRepository extends Repository<Customer, String> {

    @Query(value = "SELECT DISTINCT full_name FROM users WHERE factory_id = :factoryId AND full_name IS NOT NULL",
            nativeQuery = true)
    List<String> findPeopleNames(@Param("factoryId") String factoryId);

    @Query(value = "SELECT DISTINCT name FROM customers WHERE factory_id = :factoryId AND name IS NOT NULL",
            nativeQuery = true)
    List<String> findCustomerNames(@Param("factoryId") String factoryId);
}
