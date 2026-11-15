package com.cretas.aims.repository;

import com.cretas.aims.entity.Customer;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 实体解析 ({@code FactoryEntityResolver}) 的本厂主数据候选行 (INTENT-DISTILL-20261111 第③步 PR-5)。
 *
 * <p>JPQL (不是原生 SQL): 实体上的 {@code @Where(deleted_at IS NULL)} 生效, 已软删的客户 / 产品 / 员工不会被
 * 「解析」成当前对象 —— 与脱敏名单 ({@code FlywheelMasterDataRepository}, 含已删, fail-closed) 口径不同, 是故意的:
 * 脱敏宁多勿漏, 解析只认现存对象。</p>
 *
 * <p>🔴 每条查询都带 {@code factoryId} 条件; 名字的规范化与相等比较在 Java 里做 (规范化是全半角 / 空白 / 大小写 /
 * 单位后缀, SQL 表达不了同一份规则), 所以这里按工厂取全部有名字的行, ⛔ 不在 SQL 里做 LIKE。</p>
 *
 * <p>每行 Object[] 的列顺序见各方法注释; 解析器按列号读。停用 (isActive=false) 的行也取回: 读查询可以列出并标「已停用」,
 * 写操作由解析器排除。员工只取在职的。</p>
 */
@Transactional(readOnly = true)
public interface FactoryEntityDirectoryRepository extends Repository<Customer, String> {

    /** id, name, customerCode, shippingAddress, contactPhone, phone, isActive */
    @Query("SELECT c.id, c.name, c.customerCode, c.shippingAddress, c.contactPhone, c.phone, c.isActive FROM Customer c"
            + " WHERE c.factoryId = :factoryId AND c.name IS NOT NULL ORDER BY c.id ASC")
    List<Object[]> findCustomerRows(@Param("factoryId") String factoryId);

    /** id, name, shortName, supplierCode, address, contactPhone, phone, isActive */
    @Query("SELECT s.id, s.name, s.shortName, s.supplierCode, s.address, s.contactPhone, s.phone, s.isActive FROM Supplier s"
            + " WHERE s.factoryId = :factoryId AND s.name IS NOT NULL ORDER BY s.id ASC")
    List<Object[]> findSupplierRows(@Param("factoryId") String factoryId);

    /** id, name, code, specification, category, isActive */
    @Query("SELECT p.id, p.name, p.code, p.specification, p.category, p.isActive FROM ProductType p"
            + " WHERE p.factoryId = :factoryId AND p.name IS NOT NULL ORDER BY p.id ASC")
    List<Object[]> findProductRows(@Param("factoryId") String factoryId);

    /** id, name, code, category, unit, isActive */
    @Query("SELECT m.id, m.name, m.code, m.category, m.unit, m.isActive FROM RawMaterialType m"
            + " WHERE m.factoryId = :factoryId AND m.name IS NOT NULL ORDER BY m.id ASC")
    List<Object[]> findMaterialRows(@Param("factoryId") String factoryId);

    /** 在职员工: id, fullName, department, position, employeeCode, phone (按 id 升序, 同名列表的先后稳定)。 */
    @Query("SELECT u.id, u.fullName, u.department, u.position, u.employeeCode, u.phone FROM User u"
            + " WHERE u.factoryId = :factoryId AND u.isActive = true AND u.fullName IS NOT NULL ORDER BY u.id ASC")
    List<Object[]> findActiveEmployeeRows(@Param("factoryId") String factoryId);
}
