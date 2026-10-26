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
     * 实测的量或与出成无关的实测比例: 照常显示 (本单总量那一档)。
     * 实测出成率 / 损耗率 / 超投率 / 实际配比份额在裁剪器的 SINGLE_RUN_ACTUAL_FIELDS / AGGREGATE_ACTUAL_FIELDS 里
     * (Steve 第三问 A: 单批可看、汇总不给)。
     */
    static final Set<String> ACTUAL_RESULT_FIELDS = Set.of(
            "batchSharePct", "batchShare", "reworkRate", "wastageQty", "wastageQuantity", "auxiliaryUsage");

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
            BomRecipeDraftItem.class, BomRecipeDraftSubstitute.class, SalesOrderShortageReport.class,
            // 报工行 / 报工单 / 批次: 落库的配比核对快照与批次实测出成挂在这里 (独立审查 #2 #5)
            com.cretas.aims.entity.processentry.ProcessSheetRow.class, com.cretas.aims.entity.ProductionReport.class,
            com.cretas.aims.entity.ProductionBatch.class);

    /** 带配比类字段但不打标的实体, 写明理由。 */
    static final Map<String, String> UNTRACKED_ENTITY_LEDGER = Map.of(
            "entity/restaurant/Recipe.java", "餐饮线已废弃 (Steve 2026-10-08), 不纳入工艺保密",
            "entity/WorkProcess.java", "全厂工序目录的出成率上下限, 不属于某个产品; 保密按产品判定, 产品工序链见 ProductWorkProcess",
            "entity/workprocess/WorkProcessDraft.java", "同上: 工序目录草稿");

    /** 直接用 SQL 读配方表 (Hibernate PostLoad 看不到) 的类。 */
    static final Map<String, String> DIRECT_SQL_LEDGER = Map.ofEntries(
            Map.entry("repository/ProductProcessWorkflowRevisionRepository.java", "只取 max(revision_number), 不读工艺内容"),
            Map.entry("service/bom/impl/BomRecipeServiceImpl.java", "原生查询映射回 BomRecipeItem 实体, PostLoad 照常触发"),
            Map.entry("repository/bom/BomRecipeRepository.java", "原生 UPDATE seasoning_revision, 不读内容"),
            Map.entry("entity/bomdraft/BomRecipeDraftItem.java", "@SQLDelete 软删除语句, 不读内容"),
            Map.entry("entity/MaterialProductConversion.java", "@SQLDelete 软删除语句"),
            Map.entry("entity/ProductionBatch.java", "@SQLDelete 软删除语句"),
            Map.entry("entity/processentry/ProcessSheetRow.java", "@SQLDelete 软删除语句"),
            Map.entry("repository/ProductionReportRepository.java",
                    "产量 / 良品 / 次品 / 工时汇总 (不是配方); aggregateYieldByProcess 在服务层排除无权的保密产品并有路由闸; "
                            + "半成品加权出成由路由闸按半成品判; findYieldStandardSamples 只给标准出成自动计算 (内部)"),
            Map.entry("repository/ProductionBatchRepository.java", "计数 / 生产周期 / 完工批次总量 (单批总量, 本单档允许)"),
            Map.entry("scheduler/AnomalyDetectionScheduler.java", "厂级跨产品混合均值 (异常检测), 不按产品输出"),
            Map.entry("service/impl/AnomalyDetectionServiceImpl.java", "同上: 厂级跨产品混合均值"),
            Map.entry("service/finance/ProductionCostConsistency.java", "只取完工日期做成本一致性核对"),
            Map.entry("service/impl/ProductionPlanServiceImpl.java", "内部 UPDATE / 计数, 不输出配方"),
            Map.entry("service/impl/RawMaterialTypeServiceImpl.java", "只数转换率条数 (删原料前的引用检查)"),
            Map.entry("service/indicator/strategy/impl/FactoryActualProductionMetrics.java",
                    "按产品指标的输出带 productTypeId, 汇总出成字段按归属裁剪"),
            Map.entry("service/indicator/strategy/impl/FactoryLuDailyOutputStrategy.java", "厂级日产量合计"),
            Map.entry("service/production/FormalFinishedOutputScope.java", "只取批号做口径过滤"),
            Map.entry("service/production/ProductionProgressDashboardService.java", "按产品 / 工序的当日产量 (总量)"),
            Map.entry("service/smartbi/impl/ProductionDataExportServiceImpl.java", "导出 (另一个会话负责)"));

    /** 配方表 + 打标实体的表 (报工 / 批次的直连 SQL 汇总同样绕过打标, 独立审查第二轮 #1 #11)。 */
    static final Pattern RECIPE_TABLE_SQL = Pattern.compile(
            "(?i)(from|join|update)\\s+(bom_recipe_items|bom_seasoning_items|material_product_conversions|"
                    + "product_work_processes|workflow_task_ports|product_process_workflow_revisions|"
                    + "bom_process_injection_configs|bom_recipes|bom_recipe_draft_items|factory_material_requisition_items|"
                    + "production_reports|production_batches|process_sheet_rows)\\b");

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

    static final Pattern MAP_KEY = Pattern.compile("\\.put\\(\"([a-zA-Z]+)\"");
    /** Map.of / Map.entry 里的键 (同一行内, 后面紧跟逗号的字符串字面量)。 */
    static final Pattern INLINE_MAP_KEY = Pattern.compile("\"([a-zA-Z]+)\"\\s*,");

    /** Map 键按驼峰拆词判 (子串会把 generatedAt / operation 一类全部扫进来)。 */
    static final Set<String> SUSPICIOUS_WORDS = Set.of("ratio", "rate", "share", "usage", "dosage", "yield",
            "wastage", "conversion", "pct", "percent", "percentage");

    static boolean hasSuspiciousWord(String name) {
        for (String word : name.split("(?=[A-Z])")) {
            if (SUSPICIOUS_WORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /** Map 里拼出来的响应字段 (DTO 扫描看不见): 与配方无关的键。 */
    static final Set<String> NOT_RECIPE_MAP_KEYS = Set.of(
            // 质量 / 运营 / 系统监控 / 意图识别 / 人事薪资 / 财务指标 / 设备 / 计划进度
            "availabilityRate", "averagePassRate", "averageUsageHours", "avgMatchRate", "avgStrongSignalRate",
            "baudRate", "bracketRate", "brandLossRate", "budgetVarianceRate", "completionPct", "complianceRate",
            "complianceRatePct", "configuredMaxRatio", "constantBitRate", "costChangeRate", "coverageRate",
            "currentRatio", "debtRatio", "decisionPassRate", "directExecuteRate", "epsilonPct", "equipmentRatio",
            "equipmentPercentage", "equipmentUsageCount", "equityRatio", "expectedPct", "fillRate",
            "foodCostChangePct", "foodCostRatio", "grossMarginPercent", "hasUsageRecords", "hitRate", "inFullRate",
            "inProgressRate", "laborCostChangePct", "laborPercentage", "laborRatio", "marginRateBasis", "matchRate",
            "maxFrameRate", "memoryUsagePercent", "missingPassRateCount", "momChangePct", "momGrowthRate",
            "monthlyAvgUsage", "needFullLLMRate", "needRerankingRate", "netMarginPercent", "onlineRate",
            "otifRate", "outputChangeRate", "overallAchievementRate", "overallProgressPct", "overallProgressPercent",
            "pct", "pctChangeFromPrevMonth", "progressPct", "quickRatio", "rateUnit", "ratioName", "ratioType",
            "recoveryRate", "rentRatio", "reportedPassRateCount", "resolutionRate", "revenueChangePct",
            "showPercentage", "skillSuccessRate", "stockRatio", "teamCompletionRate", "thisMonthWastageCost",
            "totalPieceRate", "totalPieceRateWage", "totalUsageHours", "usage", "usageAvailable", "usageCount",
            "usageHistory", "usageId", "usageUnit", "usedPct", "variancePercent", "weeklyUtilizationRate",
            "yoyGrowthRate",
            // 成本构成占比 (金额口径, 价格另受价格权限), 不是用量配比
            "materialRatio", "otherCostRatio", "overheadRatio", "equipmentCostRatio", "rawMaterialPercentage",
            // 「yield」= 首检良品率 (质量), 不是出成; 其余是单位换算 / 排程 / 意图里的同名词
            "yield", "conversion", "conversionCandidates", "conversionDate", "conversionEvidence",
            // blendRatio 是整个配比核对快照对象, 里面的配方份额字段已在词表里
            "blendRatio",
            // 工作流节点配置 schema 里的参数名 (允许超额百分比 / 质检抽样率), 不是配方
            "maxExceedPercent", "samplingRate");

    @Test
    void everyRatioLikeMapKeyIsClassified() throws IOException {
        Map<String, String> unclassified = new TreeMap<>();
        forEachSource(MAIN, (path, text) -> {
            List<String> keys = new ArrayList<>();
            Matcher put = MAP_KEY.matcher(text);
            while (put.find()) keys.add(put.group(1));
            for (String line : text.split("\n")) {
                if (!line.contains("Map.of(") && !line.contains("Map.entry(")) continue;
                Matcher inline = INLINE_MAP_KEY.matcher(line);
                while (inline.find()) keys.add(inline.group(1));
            }
            for (String name : keys) {
                if (!hasSuspiciousWord(name)) continue;
                if (scrubbed(name) || ACTUAL_RESULT_FIELDS.contains(name) || NOT_RECIPE_FIELDS.contains(name)
                        || NOT_RECIPE_MAP_KEYS.contains(name)) continue;
                unclassified.putIfAbsent(name, MAIN.relativize(path).toString());
            }
        });
        assertThat(unclassified)
                .as("Map 拼出来的配比类键加进裁剪器词表; 非配方的登记在 NOT_RECIPE_MAP_KEYS")
                .isEmpty();
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
                // 实体按配方字段认 (实测出成在报工 / 批次实体上, 那是生产结果, 不是配方的载体)
                if (!isRecipeField(m.group(1))) continue;
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

    /** 裁剪器管着的字段: 配比类 / 汇总实测 (总是隐藏)、单批实测 (单据视图保留)、有歧义按上下文判的。 */
    static boolean scrubbed(String name) {
        return isRatioField(name) || contextual(name)
                || ProcessSecretPayloadScrubber.SINGLE_RUN_ACTUAL_FIELDS.contains(name);
    }

    @SuppressWarnings("unchecked")
    static boolean contextual(String name) {
        Map<String, Set<String>> map = (Map<String, Set<String>>) ReflectionTestUtils.getField(
                ProcessSecretPayloadScrubber.class, "CONTEXTUAL_RATIO_FIELDS");
        return map != null && map.containsKey(name);
    }

    @SuppressWarnings("unchecked")
    static boolean isRecipeField(String name) {
        if (ProcessSecretPayloadScrubber.RATIO_FIELDS.contains(name)) return true;
        List<String> suffixes = (List<String>) ReflectionTestUtils.getField(ProcessSecretPayloadScrubber.class,
                "RATIO_SUFFIXES");
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return suffixes != null && suffixes.stream().anyMatch(lower::endsWith);
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
                "draftId", "d", "targetProductRefType", "LIVE_PRODUCT", "targetProductRefId", "P",
                "planId", "pl").entrySet()) {
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
