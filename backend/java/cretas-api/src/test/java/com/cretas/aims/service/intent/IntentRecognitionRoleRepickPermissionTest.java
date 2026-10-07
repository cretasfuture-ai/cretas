package com.cretas.aims.service.intent;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentMatchingConfig;
import com.cretas.aims.dto.SemanticMatchResult;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.SemanticIntentMatcher;
import com.cretas.aims.service.impl.AIIntentServiceImpl;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import com.cretas.aims.service.intent.impl.IntentRecognitionPipelineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 仓库员说「查库存」—— 走<b>真的</b>识别入口 ({@link AIIntentServiceImpl#recognizeIntentWithConfidence}, SSE 与
 * {@code /execute} 调的就是这个 9 参版), 真的 Java 识别管线 + 真的精确短语表 ({@link IntentKnowledgeBase}),
 * 真的意图权限判定 ({@link IntentConfigManagementServiceImpl#hasPermission} 的 requiredRoles), 真的读写判定
 * ({@link IntentAccessModeFilter})。只桩掉: 意图配置仓储 (行内容照抄 F006 副本 2026-10-07)、工具注册表、
 * 以及外部 IO 的 embedding (语义近邻)。
 *
 * <p>缺陷: 短语表 {@code 查库存 / 看下库存 / 查一下库存 / 库存情况 → REPORT_INVENTORY} (库存报表), 库存报表只开给
 * factory_super_admin / department_admin, 执行门回「没有权限」; 而同一句话仓库员本来能办 (原料批次查询)。
 */
class IntentRecognitionRoleRepickPermissionTest {

    private static final String FACTORY = "F006";
    private static final long WAREHOUSE_USER = 1555L;
    private static final long ADMIN_USER = 1309L;

    /** 本机副本 4 句与 prod 一致落在短语表 (PHRASE_MATCH 0.96) 的说法。 */
    private static final List<String> PHRASE_HITS = List.of("查库存", "看下库存", "查一下库存", "库存情况");

    private final Map<String, AIIntentConfig> catalog = new LinkedHashMap<>();
    private IntentConfigManagementServiceImpl configService;
    private IntentAccessModeFilter accessModeFilter;
    private RoleAwareIntentRepicker repicker;
    private AIIntentServiceImpl facade;
    private SemanticIntentMatcher semantic;

    private static AIIntentConfig intent(String code, String name, String tool, String roles, String keywords) {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode(code);
        cfg.setIntentName(name);
        cfg.setIntentCategory("TEST");
        cfg.setToolName(tool);
        cfg.setRequiredRoles(roles);
        cfg.setKeywords(keywords);
        cfg.setSensitivityLevel("LOW");
        cfg.setIsActive(true);
        cfg.setPriority(0);
        return cfg;
    }

    private static ToolExecutor tool(String name, AccessMode mode) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
            @Override public AccessMode getAccessMode() { return mode; }
        };
    }

    @BeforeEach
    void setUp() {
        // 行内容照抄本机 F006 副本 ai_intent_configs (2026-10-07): required_roles 与 keywords 原样
        catalog.put("REPORT_INVENTORY", intent("REPORT_INVENTORY", "库存报表", "report_inventory",
                "[\"factory_super_admin\", \"department_admin\"]",
                "[\"库存报表\", \"库存数据\", \"存货报告\", \"仓储报表\", \"库存汇总\", \"库存分析报告\", \"物料库存报表\","
                        + " \"收发存报表\", \"库存预警汇总\", \"入库统计\"]"));
        catalog.put("MATERIAL_BATCH_QUERY", intent("MATERIAL_BATCH_QUERY", "原料批次查询", "material_batch_query",
                "[\"factory_super_admin\", \"department_admin\", \"workshop_supervisor\", \"warehouse_worker\"]",
                "[\"原料查询\", \"查看原材料\", \"原材料库存\", \"原料信息\", \"物料查询\", \"原料批次查询\", \"仓库原料\","
                        + " \"库里还有啥\", \"查询库存数量\", \"查库存\", \"查询库存\", \"看库存\", \"warehouse\", \"stock\"]"));
        AIIntentConfig adjust = intent("MATERIAL_ADJUST_QUANTITY", "调整原料数量", "material_adjust_quantity",
                "[\"factory_super_admin\"]", "[\"调整库存\", \"修改库存\"]");
        adjust.setSensitivityLevel("HIGH");
        catalog.put("MATERIAL_ADJUST_QUANTITY", adjust);
        // 所有角色都能用、只登记了裸话题词「库存」的泛化读意图 (照抄副本): 本机实测它曾把仓库员 / 操作员的
        // 库存问句全接走, 回「未返回可直接展示的明细数据」。只说了话题的词不算证据。
        catalog.put("SMART_INDICATOR_QUERY", intent("SMART_INDICATOR_QUERY", "智能指标分析", "smart_indicator_query",
                null, "[\"指标\", \"库存\", \"分析一下\"]"));
        // 仓库员能用的写意图 (领用): 用来证明「换上来的只能是读」
        catalog.put("MATERIAL_BATCH_USE", intent("MATERIAL_BATCH_USE", "领用原料", "material_batch_use",
                "[\"factory_super_admin\", \"warehouse_worker\"]", "[\"领用原料\", \"领料\"]"));
        catalog.put("MATERIAL_LOW_STOCK_ALERT", intent("MATERIAL_LOW_STOCK_ALERT", "低库存预警", "material_low_stock_alert",
                "[\"factory_super_admin\", \"department_admin\", \"warehouse_worker\"]", "[\"低库存\", \"库存预警\"]"));
        // 所有角色可用的出货查询: 本机实测是「查一下库存」的句式近邻 (「查一下出货」0.85)
        catalog.put("SHIPMENT_QUERY", intent("SHIPMENT_QUERY", "出货查询", "shipment_query", null,
                "[\"出货查询\", \"查一下出货\"]"));
        // 餐饮意图 (平台级, 不分厂): 短语向量库里与工厂短语混在一起
        AIIntentConfig restaurantStock = intent("RESTAURANT_INGREDIENT_STOCK", "食材库存", "restaurant_ingredient_stock",
                null, "[\"食材库存\", \"看看库存\"]");
        restaurantStock.setBusinessType("RESTAURANT");
        catalog.put("RESTAURANT_INGREDIENT_STOCK", restaurantStock);

        AIIntentConfigRepository repo = mock(AIIntentConfigRepository.class);
        when(repo.findByFactoryIdOrPlatformLevel(FACTORY)).thenAnswer(inv -> new ArrayList<>(catalog.values()));
        when(repo.findByIntentCodeAndFactoryIdOrPlatform(anyString(), eq(FACTORY))).thenAnswer(inv -> {
            AIIntentConfig c = catalog.get(inv.getArgument(0, String.class));
            return c == null ? List.of() : List.of(c);
        });
        when(repo.findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(catalog.get(inv.getArgument(0, String.class))));
        configService = new IntentConfigManagementServiceImpl(repo);

        ToolRegistry registry = mock(ToolRegistry.class);
        Map<String, ToolExecutor> tools = Map.of(
                "report_inventory", tool("report_inventory", AccessMode.READ),
                "material_batch_query", tool("material_batch_query", AccessMode.READ),
                "material_adjust_quantity", tool("material_adjust_quantity", AccessMode.WRITE),
                "smart_indicator_query", tool("smart_indicator_query", AccessMode.READ),
                "material_low_stock_alert", tool("material_low_stock_alert", AccessMode.READ),
                "shipment_query", tool("shipment_query", AccessMode.READ),
                "restaurant_ingredient_stock", tool("restaurant_ingredient_stock", AccessMode.READ),
                "material_batch_use", tool("material_batch_use", AccessMode.WRITE));
        when(registry.getExecutor(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tools.get(inv.getArgument(0, String.class))));
        accessModeFilter = new IntentAccessModeFilter(configService, new WriteGuardService(), registry);

        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        IntentMatchingConfig matchingConfig = mock(IntentMatchingConfig.class);
        when(matchingConfig.isRecordingEnabled()).thenReturn(false);
        IntentRecognitionPipelineServiceImpl pipeline = new IntentRecognitionPipelineServiceImpl(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null);
        ReflectionTestUtils.setField(pipeline, "matchingConfig", matchingConfig);
        ReflectionTestUtils.setField(pipeline, "knowledgeBase", kb);
        ReflectionTestUtils.setField(pipeline, "configService", configService);

        semantic = mock(SemanticIntentMatcher.class);
        when(semantic.isSemanticMatchingAvailable()).thenReturn(false);  // 默认: embedding 不可用
        repicker = new RoleAwareIntentRepicker(configService, accessModeFilter);
        ReflectionTestUtils.setField(repicker, "semanticMatcher", semantic);

        facade = new AIIntentServiceImpl(pipeline, configService, null);
        ReflectionTestUtils.setField(facade, "accessModeFilter", accessModeFilter);
        ReflectionTestUtils.setField(facade, "roleRepicker", repicker);
    }

    private static SemanticMatchResult near(String code, String phrase, double sim) {
        return SemanticMatchResult.semanticMatch(code, sim, phrase, 1L);
    }

    /**
     * 本机真实语义近邻 (2026-10-07 本机 F006 副本 + embedding 9090, findTopKMatches 阈值 0.5 的读数原样照抄)。
     * 形状要点: 餐饮的「看看库存」排在工厂意图前面; 「查一下库存」的近邻里有只像句式的「查一下出货」。
     */
    private static final Map<String, List<SemanticMatchResult>> LIVE_NEIGHBOURS = Map.of(
            "查库存", List.of(
                    near("REPORT_INVENTORY", "查库存", 1.00),
                    near("RESTAURANT_INGREDIENT_STOCK", "库存查询", 0.94),
                    near("MATERIAL_ADJUST_QUANTITY", "调整库存", 0.81)),
            "看下库存", List.of(
                    near("RESTAURANT_INGREDIENT_STOCK", "看看库存", 0.96),
                    near("MATERIAL_BATCH_QUERY", "睇下库存", 0.94),
                    near("REPORT_INVENTORY", "帮我看库存", 0.91),
                    near("MATERIAL_LOW_STOCK_ALERT", "库存还够吗", 0.83)),
            "查一下库存", List.of(
                    near("RESTAURANT_INGREDIENT_STOCK", "看看库存", 0.94),
                    near("REPORT_INVENTORY", "查库存", 0.88),
                    near("SHIPMENT_QUERY", "查一下出货", 0.87),
                    near("MATERIAL_BATCH_QUERY", "睇下库存", 0.86),
                    near("MATERIAL_ADJUST_QUANTITY", "调整库存", 0.83),
                    near("MATERIAL_LOW_STOCK_ALERT", "库存还够吗", 0.81)),
            "库存情况", List.of(
                    near("REPORT_INVENTORY", "库存情况", 1.00),
                    near("MATERIAL_BATCH_QUERY", "库存多少", 0.90),
                    near("RESTAURANT_INGREDIENT_STOCK", "库存", 0.88),
                    near("MATERIAL_LOW_STOCK_ALERT", "库存充足吗", 0.83)),
            // ⚠️ 这一条是构造的 (本机没量): 给被拒的写意图配一个仓库员能用的读近邻, 证明写不会被换成读
            "调整库存", List.of(
                    near("MATERIAL_ADJUST_QUANTITY", "调整库存", 1.00),
                    near("MATERIAL_BATCH_QUERY", "查询库存", 0.86)),
            "库存还有多少", List.of(
                    near("MATERIAL_BATCH_QUERY", "还有多少库存", 0.98),
                    near("REPORT_INVENTORY", "库存有多少", 0.94)));

    /**
     * embedding 桩 (外部 IO): 照抄本机读数。⚠️「查一下出货」本机实测 0.85, 这里抬到 0.87 排在「睇下库存」(0.86)
     * 前面 —— 让「只像句式的近邻按分数会赢」这件事在本测试里成立, 话题规则被去掉时必须红。
     */
    private void semanticNeighboursAvailable() {
        when(semantic.isSemanticMatchingAvailable()).thenReturn(true);
        when(semantic.findTopKMatches(anyString(), anyInt(), anyDouble()))
                .thenAnswer(inv -> LIVE_NEIGHBOURS.getOrDefault(inv.getArgument(0, String.class), List.of()));
    }

    private IntentMatchResult recognize(String input, long userId, String role) {
        // SSE (SseStreamingService) 调用的就是这个 9 参版, mode 为 null (App 不传), userPermissions 为 null
        return facade.recognizeIntentWithConfidence(input, FACTORY, 3, userId, role, null, null, null, List.of());
    }

    private static String code(IntentMatchResult r) {
        return r.getBestMatch() == null ? null : r.getBestMatch().getIntentCode();
    }

    private static boolean repicked(IntentMatchResult r) {
        return r.getTimingMs() != null && Long.valueOf(1L).equals(r.getTimingMs().get(RoleAwareIntentRepicker.REPICK_MARKER));
    }

    @Test
    @DisplayName("前提 (阳性对照): 不接重选时, 本测试的真管线确实把 4 句都选成库存报表, 且仓库员确实用不了它")
    void withoutRepickThePhraseTableSelectsTheReportTheWarehouseWorkerCannotUse() {
        ReflectionTestUtils.setField(facade, "roleRepicker", null);
        for (String q : PHRASE_HITS) {
            IntentMatchResult r = recognize(q, WAREHOUSE_USER, "warehouse_worker");
            assertThat(code(r)).as(q).isEqualTo("REPORT_INVENTORY");
            assertThat(r.getMatchMethod()).as(q).isEqualTo(IntentMatchResult.MatchMethod.PHRASE_MATCH);
        }
        assertThat(configService.hasPermission("REPORT_INVENTORY", "warehouse_worker")).isFalse();
        assertThat(configService.hasPermission("MATERIAL_BATCH_QUERY", "warehouse_worker")).isTrue();
    }

    @Test
    @DisplayName("仓库员「查库存」: embedding 不可用时靠意图配置里登记的说法改选原料批次查询")
    void warehouseWorkerCheckStockLandsOnMaterialBatchQueryViaConfiguredKeyword() {
        IntentMatchResult r = recognize("查库存", WAREHOUSE_USER, "warehouse_worker");
        System.out.println("[observable] 查库存 warehouse_worker -> " + code(r) + " repicked=" + repicked(r));
        assertThat(code(r)).isEqualTo("MATERIAL_BATCH_QUERY");
        assertThat(repicked(r)).isTrue();
        assertThat(configService.hasPermission(code(r), "warehouse_worker"))
                .as("换上来的意图执行门必须放行").isTrue();
    }

    @Test
    @DisplayName("构造: 他能用的写意图 (领用) 排在近邻最前面也不会被换上来, 换上来的只能是读")
    void admissibleWriteNeighbourIsNeverSubstituted() {
        when(semantic.isSemanticMatchingAvailable()).thenReturn(true);
        when(semantic.findTopKMatches(anyString(), anyInt(), anyDouble())).thenReturn(List.of(
                near("MATERIAL_BATCH_USE", "库存领用", 0.97),
                near("MATERIAL_BATCH_QUERY", "睇下库存", 0.94)));
        assertThat(configService.hasPermission("MATERIAL_BATCH_USE", "warehouse_worker"))
                .as("前提: 领用对仓库员开放, 否则本条测的是权限不是读写").isTrue();
        IntentMatchResult r = recognize("看下库存", WAREHOUSE_USER, "warehouse_worker");
        assertThat(code(r)).isEqualTo("MATERIAL_BATCH_QUERY");
    }

    @Test
    @DisplayName("仓库员 4 句短语表命中: 照本机近邻都改选原料批次查询, 跳过餐饮意图与只像句式的「查一下出货」")
    void warehouseWorkerAllPhraseHitsLandOnReadHeCanUse() {
        semanticNeighboursAvailable();
        List<String> got = new ArrayList<>();
        for (String q : PHRASE_HITS) {
            got.add(q + "=" + code(recognize(q, WAREHOUSE_USER, "warehouse_worker")));
        }
        System.out.println("[observable] warehouse_worker " + got);
        assertThat(got).containsExactly(
                "查库存=MATERIAL_BATCH_QUERY", "看下库存=MATERIAL_BATCH_QUERY",
                "查一下库存=MATERIAL_BATCH_QUERY", "库存情况=MATERIAL_BATCH_QUERY");
    }

    @Test
    @DisplayName("对照: 管理员同样 4 句仍是库存报表, 不经过重选")
    void adminKeepsInventoryReport() {
        semanticNeighboursAvailable();
        for (String q : PHRASE_HITS) {
            IntentMatchResult r = recognize(q, ADMIN_USER, "factory_super_admin");
            assertThat(code(r)).as(q).isEqualTo("REPORT_INVENTORY");
            assertThat(repicked(r)).as(q).isFalse();
        }
    }

    @Test
    @DisplayName("对照: 仓库员本来就能用的结果 (「库存还有多少」→ 原料批次查询) 原样返回")
    void permittedResultIsUntouched() {
        semanticNeighboursAvailable();
        IntentMatchResult r = recognize("库存还有多少", WAREHOUSE_USER, "warehouse_worker");
        assertThat(code(r)).isEqualTo("MATERIAL_BATCH_QUERY");
        assertThat(repicked(r)).isFalse();
    }

    @Test
    @DisplayName("阴性: 被拒的是写意图 (「调整库存」) 不换成查询 —— 执行门照旧拒绝")
    void deniedWriteIsNotSwappedForARead() {
        semanticNeighboursAvailable();
        IntentMatchResult r = recognize("调整库存", WAREHOUSE_USER, "warehouse_worker");
        assertThat(code(r)).isEqualTo("MATERIAL_ADJUST_QUANTITY");
        assertThat(repicked(r)).isFalse();
    }

    @Test
    @DisplayName("阴性: 没有能用的读意图 (操作员, 原料查询不对他开放) 就不换, 不会换成执行门也拒绝的意图")
    void roleWithNoAdmissibleReadKeepsTheDenial() {
        semanticNeighboursAvailable();
        IntentMatchResult r = recognize("查库存", 2001L, "operator");
        assertThat(code(r)).isEqualTo("REPORT_INVENTORY");
        assertThat(repicked(r)).isFalse();
        assertThat(configService.hasPermission("MATERIAL_BATCH_QUERY", "operator")).isFalse();
    }

    @Test
    @DisplayName("没有调用者身份 (role 为空, 如 /recognize 管理接口) 不重选")
    void noRoleNoRepick() {
        semanticNeighboursAvailable();
        IntentMatchResult r = recognize("查库存", WAREHOUSE_USER, null);
        assertThat(code(r)).isEqualTo("REPORT_INVENTORY");
        assertThat(repicked(r)).isFalse();
    }
}
