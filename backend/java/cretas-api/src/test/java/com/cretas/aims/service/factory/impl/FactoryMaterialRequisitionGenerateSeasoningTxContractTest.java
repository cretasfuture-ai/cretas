package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem.MaterialCategory;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-10: 「按计划生成领料单」时调料表读库失败, 不能把整张单据回滚。
 *
 * <p>只在方法体里 catch 不够 (旧代码 {@code seasoningsOfPlanBom} 就有 catch): 仓库方法自带
 * {@code @Transactional(readOnly = true)}, 以「参与」方式加入 {@code generateFromPlan} 的写事务,
 * 抛错时把<b>整个</b>事务标成 rollback-only —— 调用方接住异常、照常 save, 提交时仍
 * {@code UnexpectedRollbackException}, 主料行一起没了。
 *
 * <p>本测试用真实 Spring 事务基础设施复现这一层 (同 {@code TrainingSampleSecrecyLookupTransactionContractTest}):
 * H2 + {@link DataSourceTransactionManager} + 注解事务代理包住被测 service (generateFromPlan 上的
 * {@code @Transactional} 真的生效); 调料仓库桩的形状同 Spring Data —— 在一个<b>参与的只读事务</b>里跑
 * <b>真 SQL</b>, 表不存在时就是真的 {@code BadSqlGrammarException}。单据保存同样是真 SQL 写在外层事务的连接上,
 * 调用结束后另开连接数行 = 「真的提交了」。
 */
@DisplayName("generateFromPlan: 调料读库失败只降级调料行, 外层写事务照常提交")
class FactoryMaterialRequisitionGenerateSeasoningTxContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-TX-1";
    private static final String PRODUCT_TYPE_ID = "PT-TX-1";
    private static final String RECIPE_ID = "RECIPE-TX-1";

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Rig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:requisition_seasoning_tx_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private FactoryMaterialRequisitionService service;

    /** 调料查询那一刻所在事务的读数 (仪器: 证明它跑在哪个事务里)。 */
    private final List<Boolean> seasoningQueryReadOnly = new ArrayList<>();
    private final List<Boolean> seasoningQueryInTx = new ArrayList<>();
    /** save 那一刻所在事务的读数。 */
    private final List<Boolean> saveInWritableTx = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext();
        ctx.register(Rig.class);

        FactoryMaterialRequisitionRepository repository = mock(FactoryMaterialRequisitionRepository.class);
        ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
        BomRecipeItemRepository bomItemRepository = mock(BomRecipeItemRepository.class);
        BomSeasoningItemRepository seasoningRepository = mock(BomSeasoningItemRepository.class);
        FactoryMaterialRequisitionServiceImpl target = new FactoryMaterialRequisitionServiceImpl(
                repository, mock(FactoryMaterialRequisitionItemRepository.class), planRepository, bomItemRepository,
                mock(FactoryWarehouseRepository.class), mock(MaterialBatchRepository.class),
                mock(MaterialConsumptionRepository.class), mock(ProductionMaterialReturnRepository.class));
        ReflectionTestUtils.setField(target, "bomSeasoningItemRepository", seasoningRepository);
        ctx.registerBean("requisitionService", FactoryMaterialRequisitionServiceImpl.class, () -> target);
        ctx.refresh();

        jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
        txManager = ctx.getBean(PlatformTransactionManager.class);
        ReflectionTestUtils.setField(target, "transactionManager", txManager);
        service = ctx.getBean(FactoryMaterialRequisitionService.class);
        // 仪器自检: 被测 bean 真的套着事务代理, 否则 generateFromPlan 上的 @Transactional 不生效, 本测试量不到它要量的那一层
        assertThat(AopUtils.isAopProxy(service)).as("被测 service 没有事务代理").isTrue();

        jdbc.execute("CREATE TABLE bom_seasoning_items (recipe_id VARCHAR(64), material_type_id VARCHAR(64), "
                + "name VARCHAR(64), dosage_per_kg_g DECIMAL(14,4), seq INT)");
        jdbc.execute("CREATE TABLE factory_material_requisitions (id VARCHAR(64), requisition_no VARCHAR(64), "
                + "item_count INT)");

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PLAN-TX-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));

        BomRecipeItem raw = new BomRecipeItem();
        raw.setId(1L);
        raw.setRecipeId(RECIPE_ID);
        raw.setMaterialTypeId("MAT-PORK");
        raw.setMaterialName("猪肉");
        raw.setStandardQuantity(new BigDecimal("2"));
        raw.setUnit("kg");
        raw.setMaterialCategory("RAW");
        when(bomItemRepository.findCurrentByProduct(FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(List.of(raw));

        // 形状同 Spring Data 仓库: 方法自带只读事务、以「参与」方式加入调用方事务, 里面跑真 SQL。
        when(seasoningRepository.findByRecipeIdOrderBySeqAsc(anyString())).thenAnswer(inv -> {
            TransactionTemplate repoTx = new TransactionTemplate(txManager);
            repoTx.setReadOnly(true);
            return repoTx.execute(status -> {
                seasoningQueryInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
                seasoningQueryReadOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                List<BomSeasoningItem> out = new ArrayList<>();
                for (Map<String, Object> row : jdbc.queryForList(
                        "SELECT material_type_id, name, dosage_per_kg_g, seq FROM bom_seasoning_items "
                                + "WHERE recipe_id = ? ORDER BY seq", inv.getArgument(0, String.class))) {
                    BomSeasoningItem s = new BomSeasoningItem();
                    s.setRecipeId(RECIPE_ID);
                    s.setFactoryId(FACTORY_ID);
                    s.setMaterialTypeId((String) row.get("MATERIAL_TYPE_ID"));
                    s.setName((String) row.get("NAME"));
                    s.setDosagePerKgG((BigDecimal) row.get("DOSAGE_PER_KG_G"));
                    s.setSection(BomSeasoningItem.SECTION_INJECTION);
                    s.setSeq((Integer) row.get("SEQ"));
                    out.add(s);
                }
                return out;
            });
        });

        // 保存 = 真 SQL 写在外层事务的连接上; 外层回滚则这一行消失。
        when(repository.save(any())).thenAnswer(inv -> {
            FactoryMaterialRequisition mr = inv.getArgument(0);
            saveInWritableTx.add(TransactionSynchronizationManager.isActualTransactionActive()
                    && !TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            if (mr.getId() == null) {
                mr.setId(UUID.randomUUID().toString());
            }
            jdbc.update("INSERT INTO factory_material_requisitions (id, requisition_no, item_count) VALUES (?, ?, ?)",
                    mr.getId(), mr.getRequisitionNo(), mr.getItems().size());
            return mr;
        });
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private void seedSeasonings() {
        jdbc.update("INSERT INTO bom_seasoning_items VALUES (?, ?, ?, ?, ?)", RECIPE_ID, "SEASON-A", "香辛料", new BigDecimal("10"), 1);
        jdbc.update("INSERT INTO bom_seasoning_items VALUES (?, ?, ?, ?, ?)", RECIPE_ID, "SEASON-B", "黄油调味料", new BigDecimal("25"), 2);
    }

    /** 另开连接读: 只看得见已提交的行。 */
    private Integer committedItemCount(String requisitionId) {
        List<Integer> counts = jdbc.queryForList(
                "SELECT item_count FROM factory_material_requisitions WHERE id = ?", Integer.class, requisitionId);
        return counts.isEmpty() ? null : counts.get(0);
    }

    private int committedRequisitions() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM factory_material_requisitions", Integer.class);
        return n == null ? -1 : n;
    }

    private static List<FactoryMaterialRequisitionItem> rows(FactoryMaterialRequisition mr, MaterialCategory category) {
        return mr.getItems().stream().filter(it -> it.getMaterialCategory() == category).toList();
    }

    @Test
    @DisplayName("阳性对照: 调料表读得到 ⇒ 2 行调料按 g/kg 展开 (80kg: 0.8 / 2.0), 状态 OK, 单据已提交; 调料查询跑在独立只读事务里")
    void seasoningsAppendedWhenLookupSucceeds() {
        seedSeasonings();

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L, new BigDecimal("80"));

        assertThat(rows(mr, MaterialCategory.RAW)).extracting(FactoryMaterialRequisitionItem::getMaterialTypeId)
                .containsExactly("MAT-PORK");
        List<FactoryMaterialRequisitionItem> aux = rows(mr, MaterialCategory.AUXILIARY);
        assertThat(aux).extracting(FactoryMaterialRequisitionItem::getMaterialTypeId)
                .containsExactly("SEASON-A", "SEASON-B");
        assertThat(aux.get(0).getRequiredQty()).isEqualByComparingTo("0.800");
        assertThat(aux.get(1).getRequiredQty()).isEqualByComparingTo("2.000");
        assertThat(aux).allSatisfy(it -> assertThat(it.getRequisition()).isSameAs(mr));
        assertThat(mr.getSeasoningItemsStatus()).isEqualTo(FactoryMaterialRequisitionService.SEASONING_ITEMS_OK);
        assertThat(committedItemCount(mr.getId())).as("单据没有提交").isEqualTo(3);
        // 仪器: 调料查询确实发生, 且在一个只读事务里 (外层是写事务 ⇒ 只读说明它不是外层那个)
        assertThat(seasoningQueryInTx).containsExactly(true);
        assertThat(seasoningQueryReadOnly).as("调料查询跑在外层写事务里, 没有隔离").containsExactly(true);
        assertThat(saveInWritableTx).as("save 不在外层写事务里 —— @Transactional 没生效, 本测试在守空气").containsExactly(true);
    }

    @Test
    @DisplayName("🔴 调料表查询真的报 SQL 错 ⇒ 不抛、单据提交落库、主料行齐全、无调料行、状态 UNAVAILABLE")
    void seasoningLookupFailureDoesNotRollBackTheRequisition() {
        seedSeasonings();
        jdbc.execute("DROP TABLE bom_seasoning_items");

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L, new BigDecimal("80"));

        assertThat(rows(mr, MaterialCategory.RAW)).extracting(FactoryMaterialRequisitionItem::getMaterialTypeId)
                .containsExactly("MAT-PORK");
        assertThat(rows(mr, MaterialCategory.RAW).get(0).getRequiredQty()).isEqualByComparingTo("200");
        assertThat(rows(mr, MaterialCategory.AUXILIARY)).as("读失败不许留半截调料行").isEmpty();
        assertThat(mr.getItems()).hasSize(1);
        assertThat(mr.getSeasoningItemsStatus()).as("读失败必须标出来, 不能看起来像「配方没有调料」")
                .isEqualTo(FactoryMaterialRequisitionService.SEASONING_ITEMS_UNAVAILABLE);
        assertThat(committedItemCount(mr.getId())).as("外层写事务被拖成回滚, 单据没落库").isEqualTo(1);
        // 阳性锚点: 调料查询的确发生了 (在事务里抛的错), 否则这条在守空气
        assertThat(seasoningQueryInTx).containsExactly(true);
        assertThat(saveInWritableTx).containsExactly(true);
    }

    @Test
    @DisplayName("配方没有调料: 行为不变 —— 只有主料行, 状态 OK (不是 UNAVAILABLE), 单据提交")
    void emptySeasoningsKeepOldBehaviour() {
        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L, new BigDecimal("80"));

        assertThat(mr.getItems()).extracting(FactoryMaterialRequisitionItem::getMaterialTypeId)
                .containsExactly("MAT-PORK");
        assertThat(mr.getSeasoningItemsStatus()).isEqualTo(FactoryMaterialRequisitionService.SEASONING_ITEMS_OK);
        assertThat(committedRequisitions()).isEqualTo(1);
        assertThat(seasoningQueryInTx).containsExactly(true);
    }
}
