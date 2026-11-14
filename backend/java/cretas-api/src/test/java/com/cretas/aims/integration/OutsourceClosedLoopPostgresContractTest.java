package com.cretas.aims.integration;

import com.cretas.aims.dto.outsource.OutsourceDtos.*;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.QualityReturnOrder;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.QualityReturnStatus;
import com.cretas.aims.entity.enums.QualityReturnTargetType;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityReturnOrderRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import com.cretas.aims.service.impl.QualityReturnOrderServiceImpl;
import com.cretas.aims.service.lineage.FinishedBatchProductionAligner;
import com.cretas.aims.service.lineage.LineageEdgeOutbox;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageEdgeWriter;
import com.cretas.aims.service.outsource.OutsourceService;
import com.cretas.aims.service.quality.release.QcInspectionPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真 PostgreSQL: 委外闭环 (设计 docs/specs/outsource-closed-loop-design.md 第 8 节验收清单)。
 *
 * <ol>
 *   <li>守恒: 每种物料「源批次减少量 = 委外仓净增 + 耗用 + 损耗」, 结单时委外仓结存为 0。</li>
 *   <li>血缘: 委外仓批次 → 产出批次的 OUTSOURCE_PROCESS 边真的落库, 闭包能从产出追到原始源批次 (depth 2)。</li>
 *   <li>超发对平: 超发后直接结单 409 并列出剩余批次; 退料 + 损耗之后才能结单。</li>
 *   <li>加工费: 合格数 × 单价 = AP_INVOICE = 行累计; 不良不计费; 同一 clientRequestId 不重复挂账;
 *       退货产生待审负数 AP_ADJUSTMENT, 审批前供应商余额不变。</li>
 *   <li>阴性对照: 发料超结存 409 且源批次不动 / 耗用超结存 409 / SUBCONTRACT 未关联任务行 ship 409 / 跨厂 404。</li>
 *   <li>工序委外: 下达后工序 is_outsourced; 收货累加实际产量; 结单后工序 COMPLETED。</li>
 * </ol>
 *
 * <p>schema: 实体由 Hibernate create-drop 建; 委外三张表与加列<b>换成迁移原文</b> (含 CHECK 约束与部分唯一索引),
 * 血缘两张表也用迁移原文 (含闭包触发器)。外键全部摘掉 (一次性库)。业务调用不包事务 ⇒ afterCommit 真的触发。
 *
 * <p>开关: {@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL} (库名前缀守卫见
 * {@link DisposablePostgresTargetGuard})。本机跑法: {@code scripts/ci/run-with-test-postgres.sh}。
 */
@SpringBootTest(classes = OutsourceClosedLoopPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutsourceClosedLoopPostgresContractTest {

    static final String F = "OS-PG";
    static final String OTHER = "OS-PG-OTHER";
    static final Long USER = 20001L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({OutsourceService.class, LineageEdgeRecorder.class, LineageEdgeWriter.class, LineageEdgeOutbox.class,
            FinishedBatchProductionAligner.class, WarehouseResolver.class, QcInspectionPolicy.class,
            ArApServiceImpl.class, QualityReturnOrderServiceImpl.class,
            com.cretas.aims.service.finance.FinancePaymentReferenceLock.class})
    static class Runtime {
        /** 会计期间闸不在本测试范围: 一律开放。 */
        @Bean
        AccountingPeriodService accountingPeriodService() {
            return Mockito.mock(AccountingPeriodService.class);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutsourceService outsource;
    @Autowired QualityReturnOrderServiceImpl qualityReturns;
    @Autowired ArApServiceImpl arAp;
    @Autowired MaterialBatchRepository batches;
    @Autowired SupplierRepository suppliers;
    @Autowired ProductTypeRepository productTypes;
    @Autowired ProductionPlanRepository plans;
    @Autowired FactoryWarehouseRepository warehouses;
    @Autowired WorkProcessTaskRepository tasks;
    @Autowired QualityReturnOrderRepository qros;

    @BeforeAll
    void schema() throws Exception {
        for (String t : new String[]{"outsource_movements", "outsource_order_lines", "outsource_orders",
                "batch_lineage_closure", "batch_lineage_edges"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + t + " CASCADE");
        }
        jdbc.execute(migration("db/flyway/V20260821_02__batch_lineage_schema.sql"));
        jdbc.execute(migration("db/flyway/V20261101_43__batch_lineage_edges_refkey_unique.sql"));
        jdbc.execute(migration("db/flyway/V20261114_91__outsource_orders.sql"));
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        seed();
    }

    // ==================== 1. 整单委外 (成品): 守恒 + 超发对平 + 加工费 + 退货返工 ====================

    @Test
    @Order(1)
    void wholeOrderClosedLoopConservesStockAndChargesFeeOnGoodOnly() {
        OrderDetail d = outsource.create(F, new CreateOrderRequest("OS-SUP", LocalDate.now().plusDays(7), "PG",
                List.of(new CreateLineRequest("WHOLE", null, null, "FINISHED", "OS-FIN", "包",
                        new BigDecimal("40"), new BigDecimal("0.60"), null))), USER);
        String orderId = d.id();
        String lineId = d.lines().get(0).id();
        outsource.release(F, orderId, USER);
        String whId = jdbc.queryForObject("select outsource_warehouse_id from outsource_orders where id = ?",
                String.class, orderId);
        assertThat(jdbc.queryForObject("select type from factory_warehouses where id = ?", String.class, whId))
                .isEqualTo("OUTSOURCE");

        // 阴性对照: 发料超过源批次结存 → 409, 源批次不动
        assertThatThrownBy(() -> outsource.issue(F, orderId, lineId, new IssueRequest("REQ-ISSUE-BAD", null,
                List.of(new BatchQuantity("OS-RAW", new BigDecimal("1000")))), USER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "OUTSOURCE_ISSUE_INSUFFICIENT_STOCK");
        assertThat(used("OS-RAW")).isEqualByComparingTo("0");

        // 超发: 计划 40 包, 发 60kg 原料 + 50 个包材
        outsource.issue(F, orderId, lineId, new IssueRequest("REQ-ISSUE-1", null, List.of(
                new BatchQuantity("OS-RAW", new BigDecimal("60")),
                new BatchQuantity("OS-PACK", new BigDecimal("50")))), USER);
        assertThat(used("OS-RAW")).isEqualByComparingTo("60");
        List<Map<String, Object>> osBatches = jdbc.queryForList(
                "select id, material_type_id, status, receipt_quantity from material_batches "
                        + "where factory_id = ? and source_doc_type = 'OUTSOURCE_ISSUE' and source_doc_id = ?", F, lineId);
        assertThat(osBatches).hasSize(2).allSatisfy(r -> {
            // D13: 委外厂手里的料不是 AVAILABLE
            assertThat(r.get("status")).isEqualTo(MaterialBatchStatus.RESERVED.name());
        });
        String osRaw = idOf(osBatches, "MT-RAW");
        String osPack = idOf(osBatches, "MT-PACK");
        // 「一定会发生」: 发料的 MATERIAL_TRANSFER 边真的落库 (afterCommit 真跑了)
        assertThat(count("select count(*) from batch_lineage_edges where factory_id = ? and edge_type = 'MATERIAL_TRANSFER'"))
                .isEqualTo(2);

        // 阴性对照: 耗用超过委外仓结存 → 409
        assertThatThrownBy(() -> outsource.receive(F, orderId, lineId, new ReceiveRequest("REQ-RC-BAD", null,
                new BigDecimal("38"), new BigDecimal("2"),
                List.of(new BatchQuantity(osRaw, new BigDecimal("61")))), USER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "OUTSOURCE_BALANCE_INSUFFICIENT");

        BigDecimal supplierBefore = supplierBalance();
        ReceiveRequest rc = new ReceiveRequest("REQ-RC-1", null, new BigDecimal("38"), new BigDecimal("2"),
                List.of(new BatchQuantity(osRaw, new BigDecimal("50")), new BatchQuantity(osPack, new BigDecimal("40"))));
        outsource.receive(F, orderId, lineId, rc, USER);
        outsource.receive(F, orderId, lineId, rc, USER);   // 重复提交: 幂等命中, 不重复挂账也不重复耗料

        // 加工费 = 合格 38 × 0.60 = 22.80; 不良 2 不计费
        List<Map<String, Object>> ap = jdbc.queryForList("select amount, transaction_type from ar_ap_transactions "
                + "where factory_id = ? and source_type = 'OUTSOURCE_RECEIVE'", F);
        assertThat(ap).hasSize(1);
        assertThat((BigDecimal) ap.get(0).get("amount")).isEqualByComparingTo("22.80");
        assertThat(ap.get(0).get("transaction_type")).isEqualTo("AP_INVOICE");
        assertThat(lineFee(lineId)).isEqualByComparingTo("22.80");
        assertThat(supplierBalance().subtract(supplierBefore)).isEqualByComparingTo("22.80");
        assertThat(sumConsumption(osRaw, "OUTSOURCE_RECEIVE")).isEqualByComparingTo("50");
        assertThat(count("select count(*) from material_consumptions where factory_id = ? and production_plan_id is not null"))
                .as("委外耗用不挂计划 (成本随产出单价传递, 挂了会被计划成本再算一次)").isZero();

        // 产出: 合格成品批次单价 = (耗用成本 × 38/40 + 22.80) / 38; 耗用成本 = 50×2.00 + 40×0.10 = 104
        Map<String, Object> good = jdbc.queryForMap("select id, produced_quantity, unit_cost, status from finished_goods_batches "
                + "where factory_id = ? and status <> 'DEFECTIVE' and remark like '委外收货%'", F);
        assertThat((BigDecimal) good.get("produced_quantity")).isEqualByComparingTo("38");
        assertThat((BigDecimal) good.get("unit_cost")).isEqualByComparingTo("3.2000");   // (98.8 + 22.8) / 38
        Map<String, Object> defect = jdbc.queryForMap("select produced_quantity, unit_cost from finished_goods_batches "
                + "where factory_id = ? and status = 'DEFECTIVE'", F);
        assertThat((BigDecimal) defect.get("produced_quantity")).isEqualByComparingTo("2");
        assertThat((BigDecimal) defect.get("unit_cost")).isEqualByComparingTo("2.6000");  // 104 / 40
        String goodId = (String) good.get("id");

        // 血缘: 原始源批次 OS-RAW 是合格成品的 depth-2 祖先 (OS-RAW → 委外仓批次 → 成品)
        assertThat(count("select count(*) from batch_lineage_edges where factory_id = ? and edge_type = 'OUTSOURCE_PROCESS'"))
                .isEqualTo(4);   // 2 个委外仓批次 × (合格 + 不良)
        List<Map<String, Object>> anc = jdbc.queryForList("select ancestor_id, depth from batch_lineage_closure "
                + "where factory_id = ? and descendant_type = 'FINISHED_BATCH' and descendant_id = ? and depth > 0", F, goodId);
        assertThat(anc).anySatisfy(r -> {
            assertThat(r.get("ancestor_id")).isEqualTo("OS-RAW");
            assertThat(((Number) r.get("depth")).intValue()).isEqualTo(2);
        });

        // 退货返工 5 包: 加工费冲减 3.00 挂待审调整, 审批前供应商余额不变
        BigDecimal beforeReturn = supplierBalance();
        outsource.returnGoods(F, orderId, lineId, new GoodsReturnRequest("REQ-GR-1", null, goodId,
                new BigDecimal("5"), "封口不良"), USER, null);
        Map<String, Object> adj = jdbc.queryForMap("select amount, approval_status, transaction_type from ar_ap_transactions "
                + "where factory_id = ? and source_type = 'OUTSOURCE_GOODS_RETURN'", F);
        assertThat((BigDecimal) adj.get("amount")).isEqualByComparingTo("-3.00");
        assertThat(adj.get("approval_status")).isEqualTo("PENDING");
        assertThat(adj.get("transaction_type")).isEqualTo("AP_ADJUSTMENT");
        assertThat(supplierBalance()).isEqualByComparingTo(beforeReturn);
        assertThat(lineFee(lineId)).isEqualByComparingTo("19.80");

        // 超发对平 (阴性对照): 委外厂还剩 原料 10 + 包材 10, 另有返工待收 5 → 结单 409 并逐批列出
        assertThatThrownBy(() -> outsource.close(F, orderId, USER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "OUTSOURCE_CLOSE_BALANCE_NOT_ZERO")
                .hasMessageContaining("还剩 10")
                .hasMessageContaining("返工待收 5");

        // 返工回货 5: 不耗料
        BigDecimal rawBalanceBefore = current(osRaw);
        outsource.receive(F, orderId, lineId, new ReceiveRequest("REQ-RC-RW", null, new BigDecimal("5"),
                BigDecimal.ZERO, null), USER);
        assertThat(current(osRaw)).isEqualByComparingTo(rawBalanceBefore);
        assertThat(lineFee(lineId)).isEqualByComparingTo("22.80");

        // 退料 6 原料 (回到原批次) + 损耗 原料 4 / 包材 10 (原因必填)
        outsource.returnMaterial(F, orderId, lineId, new MaterialReturnRequest("REQ-MR-1", null,
                List.of(new BatchQuantity(osRaw, new BigDecimal("6")))), USER);
        assertThat(used("OS-RAW")).isEqualByComparingTo("54");
        assertThatThrownBy(() -> outsource.registerLoss(F, orderId, lineId, new LossRequest("REQ-LS-BAD", null,
                List.of(new BatchQuantity(osRaw, new BigDecimal("4"))), " "), USER))
                .hasFieldOrPropertyWithValue("errorCode", "OUTSOURCE_LOSS_REASON_REQUIRED");
        outsource.registerLoss(F, orderId, lineId, new LossRequest("REQ-LS-1", null, List.of(
                new BatchQuantity(osRaw, new BigDecimal("4")), new BatchQuantity(osPack, new BigDecimal("10"))),
                "委外厂报损: 封口试机"), USER);

        OrderDetail closed = outsource.close(F, orderId, USER);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.lines().get(0).status()).isEqualTo("COMPLETED");

        // 守恒: 源批次减少量 = 委外仓净增(0) + 耗用 + 损耗
        assertThat(used("OS-RAW")).isEqualByComparingTo(
                sumConsumption(osRaw, "OUTSOURCE_RECEIVE").add(sumConsumption(osRaw, "OUTSOURCE_LOSS")).add(current(osRaw)));
        assertThat(used("OS-PACK")).isEqualByComparingTo(
                sumConsumption(osPack, "OUTSOURCE_RECEIVE").add(sumConsumption(osPack, "OUTSOURCE_LOSS")).add(current(osPack)));
        assertThat(current(osRaw)).isEqualByComparingTo("0");
        assertThat(current(osPack)).isEqualByComparingTo("0");

        // 跨厂 404
        assertThatThrownBy(() -> outsource.detail(OTHER, orderId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> outsource.close(OTHER, orderId, USER)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== 2. 工序委外 (半成品): 工序任务状态与产量 ====================

    @Test
    @Order(2)
    void processOutsourceDrivesWorkProcessTask() {
        OrderDetail d = outsource.create(F, new CreateOrderRequest("OS-SUP", null, null,
                List.of(new CreateLineRequest("PROCESS", "OS-PLAN", 9001L, "SEMI", "OS-SEMI", "kg",
                        new BigDecimal("10"), new BigDecimal("1.50"), null))), USER);
        String orderId = d.id();
        String lineId = d.lines().get(0).id();
        outsource.release(F, orderId, USER);
        WorkProcessTask t = tasks.findById(9001L).orElseThrow();
        assertThat(t.getIsOutsourced()).isTrue();
        assertThat(t.getOutsourceLineId()).isEqualTo(lineId);

        outsource.issue(F, orderId, lineId, new IssueRequest("REQ-P-ISSUE", null,
                List.of(new BatchQuantity("OS-RAW2", new BigDecimal("12")))), USER);
        assertThat(tasks.findById(9001L).orElseThrow().getStatus()).isEqualTo(WorkProcessTask.Status.IN_PROGRESS);
        String os = jdbc.queryForObject("select id from material_batches where source_doc_type = 'OUTSOURCE_ISSUE' "
                + "and source_doc_id = ?", String.class, lineId);

        outsource.receive(F, orderId, lineId, new ReceiveRequest("REQ-P-RC", null, new BigDecimal("10"),
                BigDecimal.ZERO, List.of(new BatchQuantity(os, new BigDecimal("12")))), USER);
        assertThat(tasks.findById(9001L).orElseThrow().getActualQuantity()).isEqualByComparingTo("10");
        // 半成品批次: 车间仓, 单价 = (12 × 3.00 + 10 × 1.50) / 10 = 5.10
        Map<String, Object> semi = jdbc.queryForMap("select id, unit_price, product_type_id, warehouse_id, status "
                + "from material_batches where source_doc_type = 'OUTSOURCE_RECEIVE' and source_doc_id = ?", lineId);
        assertThat((BigDecimal) semi.get("unit_price")).isEqualByComparingTo("5.10");
        assertThat(semi.get("product_type_id")).isEqualTo("OS-SEMI");
        assertThat(semi.get("warehouse_id")).isEqualTo("WH-WKS-ID");
        assertThat(count("select count(*) from batch_lineage_edges where factory_id = ? and edge_type = 'OUTSOURCE_PROCESS' "
                + "and target_type = 'MATERIAL_BATCH' and target_id = '" + semi.get("id") + "'")).isEqualTo(1);

        outsource.close(F, orderId, USER);
        WorkProcessTask done = tasks.findById(9001L).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
        assertThat(done.getOutsourceFeeAmount()).isEqualByComparingTo("15.00");
    }

    // ==================== 3. 质检退回委外: 未关联任务行 fail-closed ====================

    @Test
    @Order(3)
    void subcontractQualityReturnWithoutLineCannotShip() {
        QualityReturnOrder q = new QualityReturnOrder();
        q.setId("OS-QRO-1");
        q.setFactoryId(F);
        q.setReturnNumber("QR-OS-1");
        q.setQualityInspectionId("QI-X");
        q.setTargetType(QualityReturnTargetType.SUBCONTRACT);
        q.setTargetId("OS-SUP");
        q.setQuantity(new BigDecimal("1"));
        q.setStatus(QualityReturnStatus.CONFIRMED);
        q.setCreatedBy(USER);
        qros.saveAndFlush(q);
        assertThatThrownBy(() -> qualityReturns.ship(F, "OS-QRO-1", "SF123", USER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "OUTSOURCE_LINE_REQUIRED");
        assertThat(qros.findById("OS-QRO-1").orElseThrow().getStatus()).isEqualTo(QualityReturnStatus.CONFIRMED);
    }

    // ==================== helpers ====================

    private void seed() {
        Supplier s = new Supplier();
        s.setId("OS-SUP");
        s.setFactoryId(F);
        s.setCode("OSSUP");
        s.setSupplierCode("OSSUP");
        s.setName("测试委外厂");
        s.setCurrentBalance(BigDecimal.ZERO);
        s.setIsActive(true);
        s.setCreatedBy(USER);
        suppliers.saveAndFlush(s);

        product("OS-FIN", "委外成品", "FINISHED_PRODUCT", "包");
        product("OS-SEMI", "委外半成品", "SEMI_FINISHED", "kg");

        warehouse("WH-LOG-ID", "WH-LOG", FactoryWarehouse.WarehouseType.LOGISTICS);
        warehouse("WH-WKS-ID", "WH-WKS", FactoryWarehouse.WarehouseType.WORKSHOP);
        warehouse("WH-FG-ID", "WH-FG", FactoryWarehouse.WarehouseType.FINISHED);

        batch("OS-RAW", "MT-RAW", "100", "kg", "2.00");
        batch("OS-PACK", "MT-PACK", "50", "个", "0.10");
        batch("OS-RAW2", "MT-RAW", "20", "kg", "3.00");

        ProductionPlan plan = new ProductionPlan();
        plan.setId("OS-PLAN");
        plan.setFactoryId(F);
        plan.setPlanNumber("OS-PLAN-1");
        plan.setProductTypeId("OS-SEMI");
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(USER);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        plans.saveAndFlush(plan);

        // 工序任务 (id 固定, 生产批次用假 id: 外键已摘, 服务对查不到的批次只跳过工单一致性核对)
        jdbc.update("insert into work_process_tasks (id, factory_id, production_batch_id, work_process_id, "
                + "product_type_id, process_order, status, is_outsourced, created_at, updated_at) "
                + "values (9001, ?, 88001, 'WP-SEAL', 'OS-SEMI', 2, 'PENDING', false, now(), now())", F);
    }

    private void product(String id, String name, String category, String unit) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(F);
        p.setCode(id);
        p.setName(name);
        p.setCategory(category);
        p.setProductCategory(category);
        p.setUnit(unit);
        p.setIsActive(true);
        p.setCreatedBy(USER);
        productTypes.saveAndFlush(p);
    }

    private void warehouse(String id, String code, FactoryWarehouse.WarehouseType type) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId(id);
        w.setFactoryId(F);
        w.setCode(code);
        w.setName(code);
        w.setType(type);
        w.setIsActive(true);
        warehouses.saveAndFlush(w);
    }

    private void batch(String id, String materialTypeId, String qty, String unit, String price) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber(id + "-NO");
        b.setMaterialTypeId(materialTypeId);
        b.setWarehouseId("WH-LOG-ID");
        b.setReceiptQuantity(new BigDecimal(qty));
        b.setQuantityUnit(unit);
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnitPrice(new BigDecimal(price));
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setReceiptDate(LocalDate.now());
        b.setCreatedBy(USER);
        batches.saveAndFlush(b);
    }

    private static String idOf(List<Map<String, Object>> rows, String materialTypeId) {
        return rows.stream().filter(r -> materialTypeId.equals(r.get("material_type_id")))
                .map(r -> (String) r.get("id")).findFirst().orElseThrow();
    }

    private BigDecimal used(String batchId) {
        return jdbc.queryForObject("select used_quantity from material_batches where id = ?", BigDecimal.class, batchId);
    }

    private BigDecimal current(String batchId) {
        return jdbc.queryForObject("select receipt_quantity - used_quantity - reserved_quantity from material_batches "
                + "where id = ?", BigDecimal.class, batchId);
    }

    private BigDecimal sumConsumption(String batchId, String sourceType) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from material_consumptions "
                + "where batch_id = ? and source_type = ?", BigDecimal.class, batchId, sourceType);
    }

    private BigDecimal lineFee(String lineId) {
        return jdbc.queryForObject("select fee_amount from outsource_order_lines where id = ?", BigDecimal.class, lineId);
    }

    private BigDecimal supplierBalance() {
        return jdbc.queryForObject("select coalesce(current_balance, 0) from suppliers where id = 'OS-SUP'",
                BigDecimal.class);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, F);
    }

    private static String migration(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
