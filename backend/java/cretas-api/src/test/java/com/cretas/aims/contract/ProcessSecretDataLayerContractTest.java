package com.cretas.aims.contract;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.entity.ConversionChangeHistory;
import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftSubstitute;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.inventory.SalesOrderShortageReport;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工艺保密 V2 的收敛闸: 逐接口打补丁收敛不了, 所以把「新增出口不登记就红」钉在数据层的三个口子上 ——
 * <ol>
 *   <li><b>字段</b>: 主代码里每个名字像比例 / 率 / 用量 / 出成的数值字段, 要么被统一裁剪器识别为配比类,
 *       要么登记为实测结果或非配方字段。新加一个 {@code xxxDosage} 不归类 → 红。</li>
 *   <li><b>实体</b>: 每个带配比类字段的实体, 要么被数据层打标 (读到即记录), 要么登记理由。</li>
 *   <li><b>直连 SQL</b>: 绕过 Hibernate (打标收不到) 读配方表的类必须登记理由。</li>
 * </ol>
 */
class ProcessSecretDataLayerContractTest {

    static final Path MAIN = Path.of("src/main/java/com/cretas/aims");

    static final Pattern NUMERIC_FIELD = Pattern.compile(
            "private (?:BigDecimal|Double|double|Float|float) ([a-zA-Z]+)[;= ]");
    static final Pattern SUSPICIOUS = Pattern.compile(
            "(?i)ratio|rate|share|usage|dosage|perkg|perunit|yield|wastage|conversion|pct|percent");

    /**
     * 实测结果 (某一批实际做出来的), 不是标准配方。按 Steve 待定的第三问暂按「本单结果, 照常显示」;
     * 若改为隐藏, 把它们移进裁剪器的配比类词表。
     */
    static final Set<String> ACTUAL_RESULT_FIELDS = Set.of(
            "actualYieldRate", "stepYieldRate", "cumulativeYieldRate", "realYieldRate", "todayYieldRate",
            "weightedYieldRate", "asOfYieldRate", "netYieldRate", "goodOutputRate", "sourceConsumedRatio",
            "weightSharePct", "batchSharePct", "batchShare", "inputRatioPercent", "lossRate", "avgLossRate",
            "lossPercentage", "scrapRate", "scrapLossRate", "reworkRate", "overFeedRate", "wastageQty",
            "wastageQuantity", "auxiliaryUsage");

    /**
     * 与配方无关的比例 / 率: 质量、人事薪资、准时率、系统监控、调度算法、成本与价格 (价格另受价格权限)、
     * 产品规格 (单件克重、包装换算)、卫生清洗浓度、营养成分等。逐个列名字, 不用正则家族 —— 正则会把
     * 将来新加的配方字段一起吞掉。
     */
    static final Set<String> NOT_RECIPE_FIELDS = Set.of(
            "acceptanceRate", "accuracyRate", "achievementRate", "achievementRatePct", "activationRate",
            "actualAuxCostPerUnit", "actualCostPerUnit", "actualDurationHours", "actualEquipmentCostRatio",
            "actualLaborCostPerKg", "actualLaborCostRatio", "actualMaterialCostRatio", "actualOtherCostRatio",
            "actualRatio", "afterOnTimeRate", "allocationRate", "attendanceRate", "auxOverCostPerUnit",
            "auxOverRate", "averageUsage", "avgAttendanceRate", "avgDuration",
            "avgPassRate", "avgSuccessRate", "baseHourlyRate", "baseRate", "beforeOnTimeRate", "bomLaborCostRatio",
            "bomMaterialCostRatio", "bomOverheadCostRatio", "bomVarianceRate", "boxConversionCoefficient",
            "calibratedConfidence", "carbohydratePerServing", "ccpPassRate", "ccrRate", "changePercent",
            "changePercentage", "changeRate", "changeoverRatio", "changeoverTimeRatio", "commissionRate",
            "commissionRatePct", "completionRate", "concentrationMax", "concentrationMin", "concentrationTarget",
            "contributionRate", "conversionFactor", "conversionFactorSnapshot", "conversionScore",
            "cookingFullCostPerKg", "costAllocationRatio", "costSharePct", "costVariancePct", "costVarianceRate",
            "cpuUsage", "customerComplaintRate", "defaultSamplePercentage", "defaultTaxRate", "defectRate",
            "deltaPercent", "departmentAvgRate", "deviationPercent", "deviceUtilizationRate", "discountRate",
            "diskUsage", "dominantIntentRatio", "dominantProcessRatio", "dominantRatio", "drawnRatio",
            "durationHours", "effectiveSamplingRatio", "efficiencyRatio", "employeeMedicalRate",
            "employeePensionRate", "employeeProvidentFundRate", "employeeUnemploymentRate", "employerMedicalRate",
            "employerPensionRate", "employerProvidentFundRate", "employerUnemploymentRate", "errorRate",
            "estimatedSuccessRate", "expectedOnTimeRateImprovement", "failureRate", "firstPassRate",
            "flowRateLitersPerMinute", "fluctuationPercent", "gapPercent", "globalPropertyRate", "gramsPerUnit",
            "grossMarginPct", "growthRate", "guardMaxOffsetPercent", "highRiskSamplePercentage",
            "historicalPassRate", "holidayRateMultiplier", "hourlyRate", "improvementPercent", "inFullDeliveryRate",
            "injectionCostPerKg", "laborCostPerKg", "laborCostRatio", "laborHourlyRate", "laborPerKg", "laborRate",
            "learningRate", "limitRate", "llmFallbackRate", "loadRate", "marginRate",
            "materialCostPerKg", "materialCostRatio", "materialWaitRatio", "maxAllowedRatio", "maxDefectRate",
            "maxRatio", "memoryUsage", "microPassRate", "minHistoricalPassRate", "minPassRate", "minRatio",
            "momRate", "netWeightGramsPerUnit", "nightShiftRateMultiplier", "nullRate", "onTimeDeliveryRate",
            "onTimeRate", "operationTypeBonus", "operationVolume", "orderFulfillmentRate", "outputCompletionRate",
            "overReceiveLimitRate", "overReceiveRate", "overallAchievementRatePct",
            "overallCompletionRate", "overallOnTimeRate", "overallQualityRate", "overheadCostPerKg",
            "overheadCostRatio", "overrideSamplingRatio", "overtimeRate", "overtimeRateMultiplier",
            "packagingConversionFactorSnapshot", "paddingRatio", "passRate", "penetrationScore", "perUnitBonus",
            "percentage", "pieceRateAmount", "pieceRateWage", "pmCompletionRate", "powerUsage", "precisionRate",
            "priceVarianceRate", "productivityRate", "progressPercent", "qualityPassRate", "qualityRate",
            "quotedLaborCostPerKg", "rateSnapshot", "recallRate", "residualTolerancePercent", "rewardRate",
            "salesOverShipRateProperty", "samplePercentage", "sampleRate", "samplingRatio",
            "saturatedFatPerServing", "sharedUnitCost", "skillGrowthRate",
            "standardAuxCostPerUnit", "standardCostPerUnit", "standardHourlyRate", "storageUsagePercent",
            "successRate", "successRateChange", "supportRate", "syntheticMaxRatio", "syntheticRatio",
            "targetLoadPct", "targetRatio", "taxRate", "tempWorkerRatio", "temporaryWorkerRatio", "throughputRatio",
            "todayOnTimeRate", "toleranceRate", "totalCostPerKg", "totalDurationMin", "totalVarianceRate",
            "totalWastageCost", "trainingCompletionRate", "transferReceiveOverToleranceRate", "turnoverRate",
            "urgentOnTimeRate", "urgentTaskRatio", "usageHours", "usagePercentage", "usageRate",
            "utilizationPercent", "utilizationRate", "variancePct", "varianceRate", "varianceThresholdPct",
            "waterUsage", "weightLoadRate", "weightPerUnit", "yieldRawDenominator", "yoyRate");

    /** 被数据层打标的实体: 读到即记录 (见 ProcessSecretReadTracker.readsOf)。 */
    static final List<Class<?>> TRACKED_ENTITIES = List.of(
            BomRecipe.class, BomRecipeItem.class, BomSeasoningItem.class, BomProcessInjectionConfig.class,
            BomItemSubstitute.class, BomYieldSuggestion.class, MaterialProductConversion.class,
            ConversionChangeHistory.class, ProductWorkProcess.class, ProductProcessWorkflow.class,
            ProductProcessWorkflowRevision.class, ProductionWorkflowInstance.class, WorkflowTaskPort.class,
            FactoryMaterialRequisition.class, FactoryMaterialRequisitionItem.class, BomRecipeDraft.class,
            BomRecipeDraftItem.class, BomRecipeDraftSubstitute.class, SalesOrderShortageReport.class);

    /** 带配比类字段但不打标的实体, 写明理由。 */
    static final Map<String, String> UNTRACKED_ENTITY_LEDGER = Map.of(
            "entity/restaurant/Recipe.java", "餐饮线已废弃 (Steve 2026-10-08), 不纳入工艺保密",
            "entity/WorkProcess.java", "全厂工序目录的出成率上下限, 不属于某个产品; 保密按产品判定, 产品工序链见 ProductWorkProcess",
            "entity/workprocess/WorkProcessDraft.java", "同上: 工序目录草稿");

    /** 直接用 SQL 读配方表 (Hibernate PostLoad 看不到) 的类。 */
    static final Map<String, String> DIRECT_SQL_LEDGER = Map.of(
            "repository/ProductProcessWorkflowRevisionRepository.java", "只取 max(revision_number), 不读工艺内容",
            "service/bom/impl/BomRecipeServiceImpl.java", "原生查询映射回 BomRecipeItem 实体, PostLoad 照常触发",
            "repository/bom/BomRecipeRepository.java", "原生 UPDATE seasoning_revision, 不读内容",
            "entity/bomdraft/BomRecipeDraftItem.java", "@SQLDelete 软删除语句, 不读内容");

    static final Pattern RECIPE_TABLE_SQL = Pattern.compile(
            "(?i)(from|join|update)\\s+(bom_recipe_items|bom_seasoning_items|material_product_conversion|"
                    + "product_work_process(es)?|workflow_task_ports?|product_process_workflow_revisions|"
                    + "bom_process_injection_configs?|bom_recipes|bom_recipe_draft_items|factory_material_requisition_items)\\b");

    @Test
    void everyRatioLikeNumericFieldIsClassified() throws IOException {
        Map<String, String> unclassified = new TreeMap<>();
        Set<String> seen = new TreeSet<>();
        forEachSource(MAIN, (path, text) -> {
            Matcher m = NUMERIC_FIELD.matcher(text);
            while (m.find()) {
                String name = m.group(1);
                if (!SUSPICIOUS.matcher(name).find()) continue;
                seen.add(name);
                int classes = (scrubbed(name) ? 1 : 0) + (ACTUAL_RESULT_FIELDS.contains(name) ? 1 : 0)
                        + (NOT_RECIPE_FIELDS.contains(name) ? 1 : 0);
                if (classes != 1) unclassified.put(name, MAIN.relativize(path) + " (classified " + classes + " times)");
            }
        });
        assertThat(unclassified)
                .as("配比类字段加进 ProcessSecretPayloadScrubber.RATIO_FIELDS; 实测结果 / 非配方字段登记在本测试")
                .isEmpty();
        // 台账不能留已不存在的名字 (否则会悄悄变成摆设)
        assertThat(seen).containsAll(ACTUAL_RESULT_FIELDS).containsAll(NOT_RECIPE_FIELDS);
    }

    @Test
    void everyEntityCarryingRecipeFieldsIsTrackedOrLedgered() throws IOException {
        Set<String> tracked = new TreeSet<>();
        TRACKED_ENTITIES.forEach(c -> tracked.add(c.getName().replace("com.cretas.aims.", "").replace('.', '/') + ".java"));
        List<String> missing = new ArrayList<>();
        forEachSource(MAIN.resolve("entity"), (path, text) -> {
            String relative = MAIN.relativize(path).toString().replace('\\', '/');
            Matcher m = NUMERIC_FIELD.matcher(text);
            while (m.find()) {
                // 有歧义的名字 (如实测的 yieldRate) 不算: 实体按无歧义的配比字段认
                if (!isRatioField(m.group(1))) continue;
                if (!tracked.contains(relative) && !UNTRACKED_ENTITY_LEDGER.containsKey(relative)) {
                    missing.add(relative + " -> " + m.group(1));
                }
                break;
            }
        });
        assertThat(missing).as("带配比字段的实体要在 ProcessSecretReadTracker.readsOf 里打标, 或登记理由").isEmpty();
    }

    @Test
    void everyTrackedEntityReallyRecordsAReadWhenLoaded() throws Exception {
        List<String> silent = new ArrayList<>();
        for (Class<?> type : TRACKED_ENTITIES) {
            // 每个实体一个新请求: 读数是集合, 同键会去重, 共用一个请求就分不出是谁记的
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            try {
                ProcessSecretTestHooks.record(populated(type));
                if (com.cretas.aims.service.processsecret.ProcessSecretReadTracker.reads().isEmpty()) {
                    silent.add(type.getSimpleName());
                }
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }
        assertThat(silent).as("打标清单里的实体读到时必须真的记下一条").isEmpty();
    }

    @Test
    void directSqlReadersOfRecipeTablesAreLedgered() throws IOException {
        Set<String> found = new TreeSet<>();
        forEachSource(MAIN, (path, text) -> {
            if (RECIPE_TABLE_SQL.matcher(text).find()) found.add(MAIN.relativize(path).toString().replace('\\', '/'));
        });
        assertThat(found).as("绕过 Hibernate 读配方表要登记理由 (打标收不到)").isEqualTo(DIRECT_SQL_LEDGER.keySet());
    }

    @Test
    void ratioAndQuantityVocabulariesDoNotOverlap() {
        // 重叠会让「本单总量」档把总量当配比藏掉, 或把配比当总量放出去
        assertThat(ProcessSecretPayloadScrubber.RATIO_FIELDS)
                .doesNotContainAnyElementsOf(ProcessSecretPayloadScrubber.QUANTITY_FIELDS);
    }

    // ------------------------------------------------------------------ helpers

    static boolean scrubbed(String name) {
        return isRatioField(name) || contextual(name);
    }

    @SuppressWarnings("unchecked")
    static boolean contextual(String name) {
        Map<String, Set<String>> map = (Map<String, Set<String>>) ReflectionTestUtils.getField(
                ProcessSecretPayloadScrubber.class, "CONTEXTUAL_RATIO_FIELDS");
        return map != null && map.containsKey(name);
    }

    static boolean isRatioField(String name) {
        Boolean result = ReflectionTestUtils.invokeMethod(ProcessSecretPayloadScrubber.class, "isRatioField", name);
        return Boolean.TRUE.equals(result);
    }

    static Object populated(Class<?> type) throws Exception {
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object entity = ctor.newInstance();
        for (Map.Entry<String, Object> e : Map.<String, Object>of("factoryId", "F", "productTypeId", "P",
                "recipeId", "r", "bomRecipeId", "r", "workflowInstanceId", 1L, "productionPlanId", "pl",
                "draftId", "d", "targetProductRefType", "LIVE_PRODUCT", "targetProductRefId", "P").entrySet()) {
            if (hasField(type, e.getKey()) && ReflectionTestUtils.getField(entity, e.getKey()) == null) {
                ReflectionTestUtils.setField(entity, e.getKey(), e.getValue());
            }
        }
        if (entity instanceof FactoryMaterialRequisitionItem item) {
            item.setRequisition((FactoryMaterialRequisition) populated(FactoryMaterialRequisition.class));
        }
        if (entity instanceof SalesOrderShortageReport report) {
            LineItemMatch line = new LineItemMatch();
            line.setProductTypeId("P");
            report.setAvailable(List.of(line));
        }
        return entity;
    }

    static boolean hasField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                // 继续看父类
            }
        }
        return false;
    }

    interface SourceVisitor {
        void visit(Path path, String text) throws IOException;
    }

    static void forEachSource(Path root, SourceVisitor visitor) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                visitor.visit(path, Files.readString(path, StandardCharsets.UTF_8));
            }
        }
    }
}
