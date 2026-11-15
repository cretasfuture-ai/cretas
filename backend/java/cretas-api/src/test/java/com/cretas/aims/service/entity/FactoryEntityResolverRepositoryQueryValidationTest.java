package com.cretas.aims.service.entity;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryEntityDirectoryRepository;
import com.cretas.aims.repository.intent.FlywheelMasterDataRepository;
import com.cretas.aims.service.entity.FactoryEntityResolver.Candidate;
import com.cretas.aims.service.entity.FactoryEntityResolver.Resolution;
import com.cretas.aims.service.entity.FactoryEntityResolver.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实体解析公共能力在<b>真实 JPA</b> (H2, 实体上的 {@code @Where} 软删过滤生效) 上的口径:
 * 本厂主数据规范化后精确匹配, 唯一 / 多个 (带区分字段) / 无; ⛔ 不做包含匹配; 别的工厂的同名对象不命中。
 *
 * <p>变异 (PR-5 验收): 解析改成包含匹配 → 「张三」带出「张三丰」、「华联」命中三个, 红; 只取第一个 → 同名两人变一人, 红;
 * 去掉仓储的 factory_id 条件 → 外厂的张三 / 老张家混进来, 红。阳性对照: 外厂确实有同名对象 (B 厂查得到自己的)。</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("实体解析: 本厂主数据规范化后精确匹配 (真实 JPA)")
class FactoryEntityResolverRepositoryQueryValidationTest {

    static final String A = "F-ENT-RES-A";
    static final String B = "F-ENT-RES-B";

    @Autowired TestEntityManager em;
    @Autowired FactoryEntityDirectoryRepository directory;
    @Autowired FlywheelMasterDataRepository allNames;

    FactoryEntityResolver resolver;
    User a1;
    User a2;
    Long creator;

    @BeforeEach
    void setUp() {
        resolver = new FactoryEntityResolver(directory, allNames);
        factory(A);
        factory(B);
        a1 = employee(A, "ent-a1", "张三", "加工一车间", "011", true);
        a2 = employee(A, "ent-a2", "张三", "包装车间", "027", true);
        employee(A, "ent-a3", "张三丰", "加工一车间", "012", true);      // 包含「张三」, 不同名
        employee(A, "ent-a4", "张三", "离职车间", "013", false);         // 已停用
        User gone = employee(A, "ent-a5", "张三", "软删车间", "014", true);
        employee(B, "ent-b1", "张三", "外厂车间", "099", true);
        creator = a1.getId();

        customer(A, "A-C1", "CU-A1", "老张家卤味店", "中山路 1 号", "13800001111");
        customer(A, "A-C2", "CU-A2", "华联超市", "中山路 8 号", "13800002222");
        customer(A, "A-C3", "CU-A3", "华联超市", "解放路 3 号", "13800003333");
        customer(A, "A-C4", "CU-A4", "华联批发", null, null);
        Customer deletedCustomer = customer(A, "A-C5", "CU-A5", "王记熟食店", null, null);
        customer(B, "B-C1", "CU-B1", "老张家卤味店", "外厂路", null);

        supplier(A, "A-S1", "SU-A1", "鲜禽供应站有限公司", "鲜禽站");
        product(A, "A-P1", "P-A1", "卤鸭腿", "500g/袋");
        product(B, "B-P1", "P-B1", "卤鸭腿", "1kg/袋");
        material(A, "A-M1", "M-A1", "八角");
        em.flush();
        em.getEntityManager().createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", gone.getId()).executeUpdate();
        em.getEntityManager().createNativeQuery("UPDATE customers SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", deletedCustomer.getId()).executeUpdate();
        em.clear();
    }

    @Test
    @DisplayName("同名两位员工: 都列出、按 id 排、各带车间与工号; 张三丰 / 停用 / 软删 / 外厂的张三都不在")
    void twoSameNameEmployeesWithDistinguishingFields() {
        Resolution r = resolver.resolve(A, EntityKind.EMPLOYEE, "张三");
        System.out.println("[entity-resolver] A 厂 张三 命中 " + r.matches().size() + " 个: " + r.matches());

        assertThat(r.status()).isEqualTo(Status.MULTIPLE);
        assertThat(r.matches()).extracting(Candidate::id)
                .containsExactly(String.valueOf(a1.getId()), String.valueOf(a2.getId()));
        assertThat(r.matches()).extracting(Candidate::distinguishing)
                .containsExactly("车间/部门：加工一车间，工号 011", "车间/部门：包装车间，工号 027");
        assertThat(r.matches().get(1).fields()).containsEntry("department", "包装车间").containsEntry("employeeCode", "027");
    }

    @Test
    @DisplayName("规范化: 全角 / 空白 / 大小写不影响; 但只说一个「张」不算 (不做包含匹配)")
    void normalizationButNoContainment() {
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, " 张　三 ").matches()).hasSize(2);
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, "张").status()).isEqualTo(Status.NONE);
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, "张三丰").status()).isEqualTo(Status.UNIQUE);
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, "李四").status()).isEqualTo(Status.NONE);
        assertThat(FactoryEntityResolver.matchKey(EntityKind.PRODUCT, "ＡＢＣ　牛肉"))
                .isEqualTo(FactoryEntityResolver.matchKey(EntityKind.PRODUCT, "abc牛肉"));
    }

    @Test
    @DisplayName("跨厂同名不命中: A 厂查张三 / 老张家 / 卤鸭腿只出 A 厂的; 阳性对照: B 厂查得到自己的")
    void otherFactorysSameNameIsNeverHit() {
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, "张三").matches())
                .extracting(Candidate::distinguishing).noneMatch(d -> d.contains("外厂车间"));
        assertThat(resolver.resolve(A, EntityKind.CUSTOMER, "老张家").matches())
                .extracting(Candidate::id).containsExactly("A-C1");
        assertThat(resolver.resolve(A, EntityKind.PRODUCT, "卤鸭腿").matches())
                .extracting(Candidate::id).containsExactly("A-P1");

        assertThat(resolver.resolve(B, EntityKind.EMPLOYEE, "张三").matches())
                .extracting(Candidate::distinguishing).containsExactly("车间/部门：外厂车间，工号 099");
        assertThat(resolver.resolve(B, EntityKind.CUSTOMER, "老张家卤味店").matches())
                .extracting(Candidate::id).containsExactly("B-C1");
    }

    @Test
    @DisplayName("客户: 「老张家」= 老张家卤味店 (单位后缀规范化); 两个华联超市都列出并带地址 / 电话尾号; 「华联」不命中")
    void customersBySuffixNormalizationNotContainment() {
        Resolution zhang = resolver.resolve(A, EntityKind.CUSTOMER, "老张家");
        assertThat(zhang.unique()).get().extracting(Candidate::name).isEqualTo("老张家卤味店");

        Resolution hualian = resolver.resolve(A, EntityKind.CUSTOMER, "华联 超市");
        System.out.println("[entity-resolver] A 厂 华联超市 命中 " + hualian.matches().size() + " 个");
        assertThat(hualian.status()).isEqualTo(Status.MULTIPLE);
        assertThat(hualian.matches()).extracting(Candidate::distinguishing).containsExactly(
                "客户编码 CU-A2，地址：中山路 8 号，电话尾号 2222",
                "客户编码 CU-A3，地址：解放路 3 号，电话尾号 3333");

        Resolution prefix = resolver.resolve(A, EntityKind.CUSTOMER, "华联");
        System.out.println("[entity-resolver] A 厂 华联 命中 " + prefix.matches().size() + " 个");
        assertThat(prefix.status()).as("「华联」是三个客户名的前缀, 包含匹配才会命中").isEqualTo(Status.NONE);
        assertThat(resolver.resolve(A, EntityKind.CUSTOMER, "王记熟食店").status())
                .as("软删的客户不解析成当前对象").isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("供应商按简称也算; 产品 / 物料按名精确, 「卤鸭」不命中卤鸭腿")
    void supplierShortNameProductAndMaterial() {
        assertThat(resolver.resolve(A, EntityKind.SUPPLIER, "鲜禽站").unique()).get()
                .extracting(Candidate::id).isEqualTo("A-S1");
        assertThat(resolver.resolve(A, EntityKind.SUPPLIER, "鲜禽供应站").unique()).isPresent();
        assertThat(resolver.resolve(A, EntityKind.PRODUCT, "卤鸭").status()).isEqualTo(Status.NONE);
        assertThat(resolver.resolve(A, EntityKind.PRODUCT, "卤鸭腿").unique()).get()
                .extracting(Candidate::distinguishing).isEqualTo("编码 P-A1，规格：500g/袋");
        assertThat(resolver.resolve(A, EntityKind.MATERIAL, "八角").unique()).isPresent();
    }

    @Test
    @DisplayName("脱敏名单含已软删的客户 (fail-closed), 且只含本厂; 不带工厂的解析直接拒绝")
    void redactionNamesAndFactoryScopeRequired() {
        assertThat(resolver.redactionNames(A, EntityKind.CUSTOMER))
                .contains("老张家卤味店", "华联超市", "华联批发", "王记熟食店");
        assertThat(resolver.redactionNames(B, EntityKind.CUSTOMER)).containsExactly("老张家卤味店");
        assertThat(FactoryEntityResolver.surfaceForms(EntityKind.CUSTOMER, "老张家卤味店"))
                .containsExactly("老张家卤味店", "老张家");
        assertThatThrownBy(() -> resolver.resolve(null, EntityKind.CUSTOMER, "老张家"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(" ", EntityKind.EMPLOYEE, "张三"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ fixtures

    private void factory(String id) {
        Factory f = new Factory();
        f.setId(id);
        f.setName("entity resolver " + id);
        f.setType(FactoryType.FACTORY);
        f.setLevel(0);
        f.setIsActive(true);
        f.setManuallyVerified(false);
        f.setAiWeeklyQuota(20);
        em.persist(f);
    }

    private User employee(String factoryId, String username, String fullName, String department, String code,
                          boolean active) {
        User u = new User();
        u.setFactoryId(factoryId);
        u.setUsername(username);
        u.setPasswordHash("test-only");
        u.setIsActive(active);
        u.setFullName(fullName);
        u.setDepartment(department);
        u.setEmployeeCode(code);
        em.persist(u);
        return u;
    }

    private Customer customer(String factoryId, String id, String code, String name, String address, String phone) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(factoryId);
        c.setCode(code);
        c.setCustomerCode(code);
        c.setName(name);
        c.setShippingAddress(address);
        c.setContactPhone(phone);
        c.setIsActive(true);
        c.setCreatedBy(creator);
        em.persist(c);
        return c;
    }

    private void supplier(String factoryId, String id, String code, String name, String shortName) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setFactoryId(factoryId);
        s.setCode(code);
        s.setSupplierCode(code);
        s.setName(name);
        s.setShortName(shortName);
        s.setIsActive(true);
        s.setCreatedBy(creator);
        em.persist(s);
    }

    private void product(String factoryId, String id, String code, String name, String spec) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setCode(code);
        p.setName(name);
        p.setUnit("袋");
        p.setSpecification(spec);
        p.setDailyCapacity(new BigDecimal("100"));
        p.setDailyCapacityUnit("袋");
        em.persist(p);
    }

    private void material(String factoryId, String id, String code, String name) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(factoryId);
        m.setCode(code);
        m.setName(name);
        m.setUnit("kg");
        m.setIsActive(true);
        m.setCreatedBy(creator);
        em.persist(m);
    }
}
