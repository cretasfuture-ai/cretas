package com.cretas.aims.controller.factory;

import com.cretas.aims.controller.factory.FactoryWarehouseController.CreateWarehouseRequest;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.factory.FactoryWarehouseService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验收 D-R2-01: 软删一个仓库之后, 必须能用同一个编码重建; 两个活着的同编码仓库仍然不许并存。
 *
 * <p>真实 Hibernate 上下文 + 真实 controller / service, 走的就是网页「仓储空间」和建厂对话落库的那两条路。
 * 曾经的形状: 接口只查活行 (没查到) 就去落库, 而实体上的 {@code @UniqueConstraint(factory_id, code)} 让
 * 表上的键把软删行也算进去 —— 重建在 DB 层撞键。prod 上的那条约束由 V20261103_173 换成部分唯一索引;
 * 这里守的是实体侧: 实体不能再声明全量键 (H2 据此建表, 写回去这个测试就红)。
 * 「两条活行撞键」在 DB 层的证明见 {@code FactoryWarehouseCodeUniqueMigrationPostgresContractTest}
 * (H2 建不出部分索引), 这里证明的是应用层的 409。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("D-R2-01 仓库编码: 软删后可重建, 活行仍唯一 (PR 检查必跑: *ContractTest)")
class FactoryWarehouseCodeReuseContractTest {

    private static final String F = "F-WH-REUSE";

    @Autowired TestEntityManager entityManager;
    @Autowired FactoryWarehouseRepository repository;

    private FactoryWarehouseController controller;
    private FactoryWarehouseService service;

    @BeforeEach
    void setUp() {
        controller = new FactoryWarehouseController(repository);
        service = new FactoryWarehouseService(repository);
    }

    @Test
    @DisplayName("网页路径: 建 WH-LOG → 删 → 再建 WH-LOG 成功; 再建第三次 409 WAREHOUSE_CODE_DUPLICATE")
    void webPath_softDeletedCodeCanBeRecreatedButTwoLiveOnesStillConflict() {
        FactoryWarehouse first = controller.create(F, request("WH-LOG")).getData();
        flush();
        controller.delete(F, first.getId());
        flush();

        FactoryWarehouse again = controller.create(F, request("WH-LOG")).getData();
        flush();

        assertThat(again.getId()).isNotEqualTo(first.getId());
        assertRows("WH-LOG", 2, 1);

        BusinessException dup = assertThrows(BusinessException.class,
                () -> controller.create(F, request("WH-LOG")));
        assertEquals(409, dup.getCode());
        assertEquals("WAREHOUSE_CODE_DUPLICATE", dup.getErrorCode());
        assertRows("WH-LOG", 2, 1);
    }

    @Test
    @DisplayName("网页路径: 编码前后带空格也按同一个编码判重 (查和存用同一个值)")
    void webPath_paddedCodeIsCheckedAsTheSameCode() {
        controller.create(F, request("WH-LOG"));
        flush();

        BusinessException dup = assertThrows(BusinessException.class,
                () -> controller.create(F, request(" WH-LOG ")));
        assertEquals(409, dup.getCode());
        assertEquals("WAREHOUSE_CODE_DUPLICATE", dup.getErrorCode());
        assertRows("WH-LOG", 1, 1);
    }

    @Test
    @DisplayName("建厂对话路径: 停用 (软删) 之后同编号重建成功; 活着的同编号仍 409")
    void servicePath_deactivatedCodeCanBeRecreatedButTwoLiveOnesStillConflict() {
        service.create(F, "WH-WKS", "车间仓", WarehouseType.WORKSHOP, true, null);
        flush();
        service.deactivate(F, "WH-WKS");
        flush();

        service.create(F, "WH-WKS", "车间仓", WarehouseType.WORKSHOP, true, null);
        flush();
        assertRows("WH-WKS", 2, 1);

        BusinessException dup = assertThrows(BusinessException.class,
                () -> service.create(F, "WH-WKS", "车间仓", WarehouseType.WORKSHOP, true, null));
        assertEquals(409, dup.getCode());
        assertRows("WH-WKS", 2, 1);
    }

    private static CreateWarehouseRequest request(String code) {
        CreateWarehouseRequest req = new CreateWarehouseRequest();
        req.setCode(code);
        req.setName("物流仓");
        req.setType(WarehouseType.LOGISTICS);
        return req;
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    /** 必然读数: 绕过 @Where 数全部行与活行 —— 只看「没报错」的话, 软删那条被物理删掉时读数一模一样。 */
    private void assertRows(String code, long total, long live) {
        Object[] row = (Object[]) entityManager.getEntityManager().createNativeQuery(
                        "SELECT count(*), count(*) - count(deleted_at) FROM factory_warehouses"
                                + " WHERE factory_id = :f AND code = :c")
                .setParameter("f", F)
                .setParameter("c", code)
                .getSingleResult();
        assertEquals(total, ((Number) row[0]).longValue(), code + " 总行数");
        assertEquals(live, ((Number) row[1]).longValue(), code + " 活行数");
    }
}
