package com.cretas.aims.controller.factory;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem.MaterialCategory;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-11-10: 仓管 (warehouse_worker, production 模块 = none) 打开「确认领料」, 弹窗为了自动算辅料去查
 * {@code GET /production-plans/{id}} 拿产品, 再查 BOM 调料 —— 两跳都 403, 还弹「缺少模块 production_plan」。
 *
 * <p>修法: 辅料配比由仓管本来就读得到的领料单详情带出来。本测试走<b>真入口</b>: 真的
 * {@link ModuleEnabledInterceptor} + 真的 controller + 真的 {@link FactoryMaterialRequisitionServiceImpl}
 * (只有仓储 / 保密服务是桩), 以 warehouse_worker 身份 GET 详情, 读 JSON。
 *
 * <p>工艺保密 (按厂开关, 默认关): 保密且无完整查看权 ⇒ 不给配比、状态 PROCESS_SECRET、连调料表都不读;
 * 同一桩改成有权 ⇒ 配比回来 (阳性对照, 证明「没有」不是接线断了)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactoryMaterialRequisitionPickingDosageContractTest {

    private static final String DETAIL = "/api/mobile/F006/material-requisitions/MR-1";
    private static final long WAREHOUSE_USER = 2201L;
    private static final AuthenticatedActor WAREHOUSE_ACTOR =
            new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, WAREHOUSE_USER, "F006", "warehouse_worker");

    @Mock private FactoryConfigService factoryConfigService;
    @Mock private UserModuleAccessService moduleAccess;
    @Mock private UserRepository userRepository;

    @Mock private FactoryMaterialRequisitionRepository requisitionRepository;
    @Mock private FactoryMaterialRequisitionItemRepository itemRepository;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private FactoryWarehouseRepository warehouseRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionMaterialReturnRepository productionMaterialReturnRepository;
    @Mock private BomSeasoningItemRepository seasoningRepository;
    @Mock private BomRecipeRepository recipeRepository;
    @Mock private ProcessSecretAccessService accessService;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    private ProductionPlan plan;

    @BeforeEach
    void setUp() {
        FactoryMaterialRequisitionServiceImpl service = new FactoryMaterialRequisitionServiceImpl(
                requisitionRepository, itemRepository, planRepository, bomRecipeItemRepository,
                warehouseRepository, materialBatchRepository, materialConsumptionRepository,
                productionMaterialReturnRepository);
        ReflectionTestUtils.setField(service, "bomSeasoningItemRepository", seasoningRepository);
        ReflectionTestUtils.setField(service, "processSecretAccessService", accessService);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", recipeRepository);

        ModuleEnabledInterceptor interceptor = new ModuleEnabledInterceptor(
                factoryConfigService, moduleAccess, userRepository, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new FactoryMaterialRequisitionController(service))
                .addInterceptors(interceptor)
                .build();

        // 仓管: production_plan 模块没有 (platform_role_permissions production = none), warehouse 有
        User user = new User();
        user.setId(WAREHOUSE_USER);
        user.setFactoryId("F006");
        user.setUsername("f006_warehouse_worker");
        user.setRoleCode("warehouse_worker");
        when(userRepository.findById(WAREHOUSE_USER)).thenReturn(Optional.of(user));
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(true);
        when(factoryConfigService.isModuleEnabled("F006", "warehouse")).thenReturn(true);
        when(moduleAccess.canAccessModule(user, "production_plan")).thenReturn(false);
        when(moduleAccess.canAccessModule(user, "warehouse")).thenReturn(true);

        when(requisitionRepository.findByIdAndFactoryIdAndDeletedAtIsNull("MR-1", "F006"))
                .thenReturn(Optional.of(requisition()));

        plan = new ProductionPlan();
        plan.setId("PP-1");
        plan.setFactoryId("F006");
        plan.setProductTypeId("PT-1");
        when(planRepository.findByIdAndFactoryId("PP-1", "F006")).thenAnswer(inv -> Optional.ofNullable(plan));

        BomRecipeItem bom = new BomRecipeItem();
        bom.setRecipeId("BOM-1");
        when(bomRecipeItemRepository.findCurrentByProduct("F006", "PT-1")).thenReturn(List.of(bom));
        when(seasoningRepository.findByRecipeIdOrderBySeqAsc("BOM-1")).thenReturn(List.of(
                seasoning("RMT_ONION", "83.8", BomSeasoningItem.SECTION_INJECTION, true),
                // 老汤: COOKING 段且不计入调料 —— 建单时不算, 这里也不给配比
                seasoning("RMT_SOUP", "50", BomSeasoningItem.SECTION_COOKING, false)));

        // 默认: 本厂没开工艺保密 (Steve 2026-11-03: 按厂开关, 默认全关)
        when(accessService.protectedProductTypeIds("F006")).thenReturn(Set.of());
    }

    private static FactoryMaterialRequisition requisition() {
        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setId("MR-1");
        mr.setFactoryId("F006");
        mr.setRequisitionNo("MR20261110-0001");
        mr.setProductionPlanId("PP-1");
        mr.setProductName("熟肉饼");
        List<FactoryMaterialRequisitionItem> items = new ArrayList<>();
        items.add(item("RAW-1", "RMT_BEEF", MaterialCategory.RAW));
        items.add(item("AUX-1", "RMT_ONION", MaterialCategory.AUXILIARY));
        items.add(item("AUX-2", "RMT_SOUP", MaterialCategory.AUXILIARY));
        items.add(item("PKG-1", "RMT_BOX", MaterialCategory.PACKAGING));
        mr.setItems(items);
        return mr;
    }

    private static FactoryMaterialRequisitionItem item(String id, String materialTypeId, MaterialCategory category) {
        FactoryMaterialRequisitionItem it = new FactoryMaterialRequisitionItem();
        it.setId(id);
        it.setMaterialTypeId(materialTypeId);
        it.setMaterialCategory(category);
        it.setUnit("kg");
        return it;
    }

    private static BomSeasoningItem seasoning(String materialTypeId, String dosage, String section, boolean countIn) {
        BomSeasoningItem s = new BomSeasoningItem();
        s.setRecipeId("BOM-1");
        s.setMaterialTypeId(materialTypeId);
        s.setDosagePerKgG(new BigDecimal(dosage));
        s.setSection(section);
        s.setCountInSeasoning(countIn);
        return s;
    }

    private JsonNode getDetailAsWarehouseWorker() throws Exception {
        String body = mockMvc.perform(get(DETAIL)
                        .requestAttr("userId", WAREHOUSE_USER)
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, WAREHOUSE_ACTOR))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    /** itemId → dosagePerKgG (字段不存在的行不进 map)。 */
    private static Map<String, BigDecimal> dosages(JsonNode data) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (JsonNode it : data.path("items")) {
            if (it.has("dosagePerKgG")) out.put(it.path("id").asText(), it.path("dosagePerKgG").decimalValue());
        }
        return out;
    }

    @Test
    @DisplayName("阳性: warehouse_worker 读领料单详情 200, 辅料行带配比 (只有计入调料的那味), 状态 AVAILABLE")
    void warehouseWorkerGetsAuxiliaryDosageFromRequisitionDetail() throws Exception {
        JsonNode data = getDetailAsWarehouseWorker();

        assertThat(data.path("items")).as("详情没带明细 —— 后面的断言无意义").hasSize(4);
        assertThat(dosages(data)).as("只有洋葱泥该带配比; 原料 / 包材 / 不计入调料的老汤都不带")
                .containsOnlyKeys("AUX-1");
        assertThat(dosages(data).get("AUX-1")).isEqualByComparingTo("83.8");
        assertThat(data.path("auxiliaryAutoCalc").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("🔴 保密开且仓管无完整查看权: 详情仍 200, 但不带任何配比、状态 PROCESS_SECRET, 调料表一行不读")
    void processSecretOnAndNotAuthorizedOmitsDosage() throws Exception {
        when(accessService.protectedProductTypeIds("F006")).thenReturn(Set.of("PT-1"));
        when(accessService.permitsViewFull(any(), eq("F006"), eq("PT-1"), anyString())).thenReturn(false);

        JsonNode data = getDetailAsWarehouseWorker();

        assertThat(data.path("items")).hasSize(4);
        assertThat(dosages(data)).as("保密产品的配比漏给了无权的仓管").isEmpty();
        assertThat(data.path("auxiliaryAutoCalc").asText()).isEqualTo("PROCESS_SECRET");
        // 判的是这位仓管、这个产品 (不是恒真的桩: 换成别的身份 / 产品就不命中)
        verify(accessService, atLeastOnce()).permitsViewFull(eq(WAREHOUSE_ACTOR), eq("F006"), eq("PT-1"), anyString());
        verify(seasoningRepository, never()).findByRecipeIdOrderBySeqAsc(anyString());
    }

    @Test
    @DisplayName("阳性对照: 同样保密, 但调用者有完整查看权 (负责人 / 有效授权) ⇒ 配比照常给")
    void processSecretOnButAuthorizedKeepsDosage() throws Exception {
        when(accessService.protectedProductTypeIds("F006")).thenReturn(Set.of("PT-1"));
        when(accessService.permitsViewFull(eq(WAREHOUSE_ACTOR), eq("F006"), eq("PT-1"), anyString())).thenReturn(true);

        JsonNode data = getDetailAsWarehouseWorker();

        assertThat(dosages(data)).containsOnlyKeys("AUX-1");
        assertThat(data.path("auxiliaryAutoCalc").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("🔴 多产出计划: 锚点不保密、钉住配方的成品保密且无权 ⇒ 仍按保密处理 (不能只判锚点)")
    void pinnedFinishedGoodIsCheckedNotJustTheAnchor() throws Exception {
        plan.setProductTypeId("RMT_ANCHOR");
        plan.setSelectedBomRecipeIdsByProduct(new java.util.LinkedHashMap<>(Map.of("PT-FG", "BOM-FG")));
        BomRecipe fgRecipe = new BomRecipe();
        fgRecipe.setId("BOM-FG");
        fgRecipe.setFactoryId("F006");
        fgRecipe.setProductTypeId("PT-FG");
        when(recipeRepository.findById("BOM-FG")).thenReturn(Optional.of(fgRecipe));
        when(accessService.protectedProductTypeIds("F006")).thenReturn(Set.of("PT-FG"));
        when(accessService.permitsViewFull(any(), eq("F006"), eq("RMT_ANCHOR"), anyString())).thenReturn(true);
        when(accessService.permitsViewFull(any(), eq("F006"), eq("PT-FG"), anyString())).thenReturn(false);

        JsonNode data = getDetailAsWarehouseWorker();

        assertThat(dosages(data)).isEmpty();
        assertThat(data.path("auxiliaryAutoCalc").asText()).isEqualTo("PROCESS_SECRET");
    }

    @Test
    @DisplayName("计划已删: 详情照常 200, 不给配比 (NO_RATIO), 不报错")
    void deletedPlanGivesNoRatioWithoutError() throws Exception {
        plan = null;

        JsonNode data = getDetailAsWarehouseWorker();

        assertThat(data.path("items")).hasSize(4);
        assertThat(dosages(data)).isEmpty();
        assertThat(data.path("auxiliaryAutoCalc").asText()).isEqualTo("NO_RATIO");
    }
}
