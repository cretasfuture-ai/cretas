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
import com.cretas.aims.service.entity.FactoryEntityResolver.Purpose;
import com.cretas.aims.service.entity.FactoryEntityResolver.Resolution;
import com.cretas.aims.service.entity.FactoryEntityResolver.Status;
import com.cretas.aims.service.entity.FactoryEntityResolver.SuggestionBasis;
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
 * 实体解析公共能力在<b>真实 JPA</b> (H2, 实体上的 {@code @Where} 软删过滤生效) 上的口径 (organizer 终审 #1777):
 * 第一层全名精确 (NFKC / 空白 / 大小写) 才算解析出来, 唯一 / 多个 (带区分字段) / 无; 全名零命中时才给「称呼核心」
 * 与包含两层的候选, 候选 ⛔ 不进 matches; 写操作只用全名层并跳过停用; 读操作返回停用并标「已停用」;
 * 别的工厂的同名对象不命中。
 *
 * <p>变异 (PR-5 验收): M-two-tier 去掉全名层只用称呼核心 → 「新世纪集团」被当成新世纪超市、「老张家卤味店」
 * 带出熟食店, 红; M-no-suggest 零命中不给候选 → 「华联」「新世纪集团」「老张家」的候选断言红;
 * 去掉仓储的 factory_id 条件 → 外厂的张三 / 老张家混进来, 红。阳性对照: 外厂确实有同名对象 (B 厂查得到自己的)。</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("实体解析: 全名精确 + 零命中才给候选 (真实 JPA)")
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

        customer(A, "A-C1", "CU-A1", "老张家卤味店", "中山路 1 号", "13800001111", true);
        customer(A, "A-C2", "CU-A2", "华联超市", "中山路 8 号", "13800002222", true);
        customer(A, "A-C3", "CU-A3", "华联超市", "解放路 3 号", "13800003333", true);
        customer(A, "A-C4", "CU-A4", "华联批发", null, null, true);
        Customer deletedCustomer = customer(A, "A-C5", "CU-A5", "王记熟食店", null, null, true);
        customer(A, "A-C6", "CU-A6", "老张家熟食店", "北门 2 号", null, true);
        customer(A, "A-C7", "CU-A7", "新世纪超市", "东环路 5 号", null, true);
        customer(A, "A-C8", "CU-A8", "旭日商行", "西街 9 号", null, false);  // 已停用
        customer(B, "B-C1", "CU-B1", "老张家卤味店", "外厂路", null, true);

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
        assertThat(r.suggestions()).as("全名有命中就不给候选").isEmpty();
    }

    @Test
    @DisplayName("规范化: 全角 / 空白 / 大小写不影响; 只说一个「张」不算, 员工也不按包含给候选")
    void normalizationButNoContainmentForEmployees() {
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, " 张　三 ").matches()).hasSize(2);
        Resolution zhang = resolver.resolve(A, EntityKind.EMPLOYEE, "张");
        assertThat(zhang.status()).isEqualTo(Status.NONE);
        assertThat(zhang.suggestions()).isEmpty();
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, "张三丰").status()).isEqualTo(Status.UNIQUE);
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, "李四").status()).isEqualTo(Status.NONE);
        assertThat(FactoryEntityResolver.fullKey("ＡＢＣ　牛肉")).isEqualTo(FactoryEntityResolver.fullKey("abc牛肉"));
        assertThat(resolver.resolve(A, EntityKind.CUSTOMER, "新世纪　超市").unique()).get()
                .extracting(Candidate::id).isEqualTo("A-C7");
    }

    @Test
    @DisplayName("本厂只有「新世纪超市」, 说「新世纪集团」→ 不解析成它, 只作「你是不是要找」的称呼核心候选")
    void differentFullNameIsNotResolvedOnlySuggested() {
        Resolution r = resolver.resolve(A, EntityKind.CUSTOMER, "新世纪集团");
        System.out.println("[entity-resolver] A 厂 新世纪集团 matches=" + r.matches().size()
                + " suggestions=" + r.suggestions().size() + " basis=" + r.basis());
        assertThat(r.status()).isEqualTo(Status.NONE);
        assertThat(r.matches()).isEmpty();
        assertThat(r.basis()).isEqualTo(SuggestionBasis.CORE);
        assertThat(r.suggestions()).extracting(Candidate::id).containsExactly("A-C7");

        assertThat(resolver.resolve(A, EntityKind.CUSTOMER, "新世纪集团", Purpose.WRITE).suggestions())
                .as("写操作只用全名层, 不给候选").isEmpty();
    }

    @Test
    @DisplayName("「老张家卤味店」+「老张家熟食店」: 说全名 → 唯一命中卤味店; 只说「老张家」→ 无命中, 两家都是候选")
    void fullNameWinsOverSharedCore() {
        Resolution full = resolver.resolve(A, EntityKind.CUSTOMER, "老张家卤味店");
        System.out.println("[entity-resolver] A 厂 老张家卤味店 matches=" + full.matches());
        assertThat(full.status()).isEqualTo(Status.UNIQUE);
        assertThat(full.unique()).get().extracting(Candidate::id).isEqualTo("A-C1");
        assertThat(full.suggestions()).isEmpty();

        Resolution core = resolver.resolve(A, EntityKind.CUSTOMER, "老张家");
        assertThat(core.status()).as("称呼核心不算解析出来").isEqualTo(Status.NONE);
        assertThat(core.basis()).isEqualTo(SuggestionBasis.CORE);
        assertThat(core.suggestions()).extracting(Candidate::id).containsExactly("A-C1", "A-C6");
    }

    @Test
    @DisplayName("「华联」全名零命中 → 三个包含候选, 不自动选; 「华联 超市」全名 → 两个都列出带地址 / 电话尾号")
    void containsOnlySuggestsNeverPicks() {
        Resolution prefix = resolver.resolve(A, EntityKind.CUSTOMER, "华联");
        System.out.println("[entity-resolver] A 厂 华联 matches=" + prefix.matches().size()
                + " suggestions=" + prefix.suggestions().size() + " basis=" + prefix.basis());
        assertThat(prefix.status()).isEqualTo(Status.NONE);
        assertThat(prefix.unique()).isEmpty();
        assertThat(prefix.basis()).isEqualTo(SuggestionBasis.CONTAINS);
        assertThat(prefix.suggestions()).extracting(Candidate::id).containsExactly("A-C2", "A-C3", "A-C4");

        Resolution hualian = resolver.resolve(A, EntityKind.CUSTOMER, "华联 超市");
        assertThat(hualian.status()).isEqualTo(Status.MULTIPLE);
        assertThat(hualian.matches()).extracting(Candidate::distinguishing).containsExactly(
                "客户编码 CU-A2，地址：中山路 8 号，电话尾号 2222",
                "客户编码 CU-A3，地址：解放路 3 号，电话尾号 3333");

        Resolution deleted = resolver.resolve(A, EntityKind.CUSTOMER, "王记熟食店");
        assertThat(deleted.status()).as("软删的客户不解析成当前对象").isEqualTo(Status.NONE);
        assertThat(deleted.suggestions()).as("软删的也不当候选").isEmpty();
    }

    @Test
    @DisplayName("停用客户: 读查询命中并标「已停用」; 写操作不命中、不给候选")
    void inactiveCustomerReadMarkedWriteSkipped() {
        Resolution read = resolver.resolve(A, EntityKind.CUSTOMER, "旭日商行", Purpose.READ);
        assertThat(read.unique()).get().satisfies(c -> {
            assertThat(c.active()).isFalse();
            assertThat(c.distinguishing()).endsWith("已停用");
        });

        Resolution write = resolver.resolve(A, EntityKind.CUSTOMER, "旭日商行", Purpose.WRITE);
        System.out.println("[entity-resolver] A 厂 旭日商行(停用) WRITE matches=" + write.matches().size());
        assertThat(write.status()).isEqualTo(Status.NONE);
        assertThat(write.suggestions()).isEmpty();
        assertThat(resolver.resolve(A, EntityKind.CUSTOMER, "新世纪超市", Purpose.WRITE).unique())
                .as("阳性对照: 在用客户写操作照常命中").isPresent();
    }

    @Test
    @DisplayName("跨厂同名不命中: A 厂查张三 / 老张家卤味店 / 卤鸭腿只出 A 厂的; 阳性对照: B 厂查得到自己的")
    void otherFactorysSameNameIsNeverHit() {
        assertThat(resolver.resolve(A, EntityKind.EMPLOYEE, "张三").matches())
                .extracting(Candidate::distinguishing).noneMatch(d -> d.contains("外厂车间"));
        assertThat(resolver.resolve(A, EntityKind.CUSTOMER, "老张家卤味店").matches())
                .extracting(Candidate::id).containsExactly("A-C1");
        assertThat(resolver.resolve(A, EntityKind.CUSTOMER, "老张家").suggestions())
                .extracting(Candidate::id).doesNotContain("B-C1");
        assertThat(resolver.resolve(A, EntityKind.PRODUCT, "卤鸭腿").matches())
                .extracting(Candidate::id).containsExactly("A-P1");

        assertThat(resolver.resolve(B, EntityKind.EMPLOYEE, "张三").matches())
                .extracting(Candidate::distinguishing).containsExactly("车间/部门：外厂车间，工号 099");
        assertThat(resolver.resolve(B, EntityKind.CUSTOMER, "老张家卤味店").matches())
                .extracting(Candidate::id).containsExactly("B-C1");
    }

    @Test
    @DisplayName("供应商按简称全名也算; 「鲜禽供应站」只是称呼核心候选; 产品「卤鸭」只是包含候选")
    void supplierShortNameProductAndMaterial() {
        assertThat(resolver.resolve(A, EntityKind.SUPPLIER, "鲜禽站").unique()).get()
                .extracting(Candidate::id).isEqualTo("A-S1");
        Resolution core = resolver.resolve(A, EntityKind.SUPPLIER, "鲜禽供应站");
        assertThat(core.status()).isEqualTo(Status.NONE);
        assertThat(core.basis()).isEqualTo(SuggestionBasis.CORE);
        assertThat(core.suggestions()).extracting(Candidate::id).containsExactly("A-S1");

        Resolution duck = resolver.resolve(A, EntityKind.PRODUCT, "卤鸭");
        assertThat(duck.status()).isEqualTo(Status.NONE);
        assertThat(duck.basis()).isEqualTo(SuggestionBasis.CONTAINS);
        assertThat(duck.suggestions()).extracting(Candidate::id).containsExactly("A-P1");
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

    private Customer customer(String factoryId, String id, String code, String name, String address, String phone,
                              boolean active) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(factoryId);
        c.setCode(code);
        c.setCustomerCode(code);
        c.setName(name);
        c.setShippingAddress(address);
        c.setContactPhone(phone);
        c.setIsActive(active);
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
