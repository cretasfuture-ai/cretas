package com.cretas.aims.blueprint.interview;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.ConversionEvidence;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.entity.workprocess.WorkProcessCustomFieldSchema;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.canvas.ThresholdKeyCatalog;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import java.math.BigDecimal;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 建厂落库载荷的<b>全量校验</b> —— 预览与落库共用这一份。
 *
 * <h2>为什么单独成类(2026-08-12 从 controller 抽出)</h2>
 *
 * 这些 check* 原先与端点、计划计算、落库写入一起挤在
 * {@code TemplatePackageController}(3536 行)里。那不是模块边界的问题, 是
 * <b>单文件瓶颈</b>: 建厂相关的每一项改动都要动同一个文件, 于是只能串行 ——
 * 2026-08-11 有 6 张欠账表被迫排队, 2026-08-12 一轮里又有两项
 * (包装换算 guard / 模块配置)撞在这一个文件上。
 *
 * <h2>⛔ 这次搬迁不改行为</h2>
 *
 * 每个方法都是<b>原文逐字</b>搬过来的, 只把跨类调用的三个入口从 private 提到 public。
 * 校验顺序、problems 的措辞与拼接顺序全部不变 —— 那些字符串客户直接看得见,
 * 也正是拆分前后逐字比对的判据。
 *
 * <h2>事务</h2>
 *
 * 本类<b>只读</b>, 自己不开事务。它仍然跑在调用方的事务里:
 * apply 那条路由 {@link FactoryBuildApplyService#apply} 开事务(与拆分前
 * controller 方法上的 {@code @Transactional} 同一个跨度), preview 那条路原先就没有事务。
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class FactoryBuildApplyValidator {

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private WorkProcessRepository workProcessRepository;

    @Autowired
    private MaterialCodeSegmentRepository codeSegmentRepository;

    /** 见 {@code checkPackaging}: 包装换算两端的单位都要过权威表。 */
    @Autowired
    private UnitContractService unitContractService;

    @Autowired
    private AssemblyThresholdWriter assemblyThresholdWriter;

    private static final Map<String, String> STORAGE_LABELS = Map.of(
            "fresh", "新鲜", "frozen", "冻货", "dry", "干货", "normal", "常温");

    public static String materialStorageLabel(String value) {
        return value == null ? null : STORAGE_LABELS.get(value);
    }

    void checkMaterialStorage(List<String> problems, String factoryId, List<ApplyAIInitRequest.TypeItem> items) {
        if (items == null) return;
        for (var item : items) {
            if (item == null || isBlank(item.getCode()) || isBlank(item.getName())) continue;
            boolean packaging = com.cretas.aims.entity.material.MaterialMainCategory.PACKAGING.equals(
                    com.cretas.aims.entity.material.MaterialMainCategory.of(item.getCategory()));
            if (packaging && !isBlank(item.getStorageType())) {
                problems.add("包材「" + item.getName() + "」不适用储存类型，请清空后重新保存");
                continue;
            }
            if (!isBlank(item.getStorageType()) && materialStorageLabel(item.getStorageType()) == null) {
                problems.add("物料「" + item.getName() + "」的储存类型无效，请明确选择新鲜、冻货、干货或常温");
                continue;
            }
            boolean exists = rawMaterialTypeRepository.findByFactoryIdAndCode(factoryId, item.getCode()).isPresent()
                    || rawMaterialTypeRepository.existsByFactoryIdAndNormalizedName(factoryId,
                        com.cretas.aims.util.EntityNameNormalizer.normalize(item.getName()));
            if (!exists && !packaging && isBlank(item.getStorageType())) {
                problems.add("物料「" + item.getName() + "」尚未确认储存类型，请在专业复核的物料表中选择后重新保存");
            }
        }
    }

    /** 返回全部不合规之处(不是遇到第一个就返回) —— 让提交方一次看清要改哪些。 */
    public List<String> validateApplyRequest(ApplyAIInitRequest req, String factoryId) {
        List<String> problems = new ArrayList<>();
        if (req.getSchemas() == null || req.getSchemas().isEmpty()) {
            problems.add("schemas 为空");
        } else {
            for (int i = 0; i < req.getSchemas().size(); i++) {
                ApplyAIInitRequest.SchemaItem s = req.getSchemas().get(i);
                if (isBlank(s.getEntityType())) {
                    problems.add("schemas[" + i + "].entityType 为空");
                } else if (!ALLOWED_ENTITY_TYPES.contains(s.getEntityType())) {
                    problems.add("schemas[" + i + "].entityType=" + s.getEntityType()
                            + " 不在允许集合 " + ALLOWED_ENTITY_TYPES);
                }
                if (isBlank(s.getEntityName())) {
                    problems.add("schemas[" + i + "].entityName 为空");
                }
                if (s.getFields() == null || s.getFields().isEmpty()) {
                    problems.add("schemas[" + i + "].fields 为空");
                }
            }
        }
        if (req.getSuggestedData() != null) {
            // code/name/unit 三列在 product_types / raw_material_types 都是 NOT NULL。
            // ⛔ 缺单位不补默认值 —— 编一个「kg」就是替客户编数据。
            checkTypeItems(problems, "productTypes",
                    req.getSuggestedData().getProductTypes());
            checkTypeItems(problems, "materialTypes",
                    req.getSuggestedData().getMaterialTypes());
            checkMaterialStorage(problems, factoryId, req.getSuggestedData().getMaterialTypes());
            checkPackaging(problems, factoryId, req.getSuggestedData().getProductTypes());
            checkConversions(problems, req.getSuggestedData(), factoryId);
            checkWorkProcesses(problems, req.getSuggestedData().getWorkProcesses());
            checkModules(problems, req.getSuggestedData().getModules());
            checkThresholds(problems, factoryId, req.getSuggestedData().getThresholds());
            checkWarehouses(problems, req.getSuggestedData().getWarehouses());
            checkCodeSegments(problems, req.getSuggestedData().getCodeSegments());
            checkAlerts(problems, req.getSuggestedData().getAlerts());
            checkMetricWatches(problems, req.getSuggestedData().getMetricWatches());
            checkModuleConfigs(problems, req.getSuggestedData().getModuleConfigs());
            checkYieldScale(problems, req.getSuggestedData().getWorkProcesses());
            Set<String> roleConflicts = new HashSet<>();
            deriveMaterialRoles(req.getSuggestedData(), roleConflicts);
            for (String name : roleConflicts) {
                problems.add("物料「" + name + "」同时被当成辅料和包材 —— "
                        + "必须先定死是哪一类, 挑错会让整套包材规则(单价/存储方式/包装层级)全用错");
            }
            checkProductProcesses(problems, req.getSuggestedData(), factoryId);
        }
        return problems;
    }

    /** Canonical Blueprint 的跨对象语义门禁；只读且不替用户补业务事实。 */
    public List<BlueprintSemanticIssue> validateBlueprintSemantics(
            ApplyAIInitRequest req, String factoryId, String customerSupplied,
            List<ConversionEvidence> conversionEvidence) {
        if (req == null || req.getSuggestedData() == null) {
            return List.of();
        }
        ApplyAIInitRequest.SuggestedData sd = req.getSuggestedData();
        List<BlueprintSemanticIssue> issues = new ArrayList<>();

        if (sd.getConversionRates() != null) {
            for (ApplyAIInitRequest.ConversionItem conversion : sd.getConversionRates()) {
                if (conversion == null) {
                    continue;
                }
                String provenance = conversionProvenance(
                        conversionEvidence, conversion.getFromCode(), conversion.getToCode());
                String pair = (conversion.getFromCode() == null ? "?" : conversion.getFromCode())
                        + "→" + (conversion.getToCode() == null ? "?" : conversion.getToCode());
                if (!Set.of("USER_CONFIRMED", "CALCULATED").contains(provenance)) {
                    issues.add(new BlueprintSemanticIssue(
                            "CONVERSION_PROVENANCE_REQUIRED", "packaging",
                            "转换率 " + pair + " 只有 Agent 推演值，尚未得到用户确认或可解释公式",
                            "删除这条转换率，或在细节配置中选择“用户明确确认”/“按公式计算”并核对数值"));
                } else if ("CALCULATED".equals(provenance) && isBlank(conversion.getNote())) {
                    issues.add(new BlueprintSemanticIssue(
                            "CONVERSION_FORMULA_REQUIRED", "packaging",
                            "转换率 " + pair + " 标记为公式计算，但没有记录公式或口径",
                            "填写净含量、出成率和单位换算公式后重新校验"));
                }
            }
        }

        Map<String, Boolean> modules = sd.getModules() == null ? Map.of() : sd.getModules();
        if (sd.getModuleConfigs() != null) {
            for (ApplyAIInitRequest.ModuleConfigItem module : sd.getModuleConfigs()) {
                if (module == null || !"equipment".equalsIgnoreCase(module.getModuleId())) {
                    continue;
                }
                Boolean legacySwitch = modules.get("enableEquipmentManagement");
                if (legacySwitch != null && module.getEnabled() != null
                        && !legacySwitch.equals(module.getEnabled())) {
                    issues.add(new BlueprintSemanticIssue(
                            "MODULE_SWITCH_CONTRADICTION", "modules",
                            "设备模块在功能开关中为" + (legacySwitch ? "开启" : "关闭")
                                    + "，但在模块配置中为" + (module.getEnabled() ? "开启" : "关闭"),
                            "让 enableEquipmentManagement 与 equipment 模块配置保持同一开关值"));
                }
            }
        }

        Map<String, ApplyAIInitRequest.TypeItem> products = Optional.ofNullable(sd.getProductTypes())
                .orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .filter(product -> !isBlank(product.getCode()))
                .collect(Collectors.toMap(product -> product.getCode().trim(), product -> product,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, ApplyAIInitRequest.WorkProcessItem> processes = Optional.ofNullable(sd.getWorkProcesses())
                .orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .filter(process -> !isBlank(process.getName()))
                .collect(Collectors.toMap(process -> process.getName().trim(), process -> process,
                        (left, right) -> left, LinkedHashMap::new));
        for (ApplyAIInitRequest.ProductProcessItem route
                : Optional.ofNullable(sd.getProductProcesses()).orElse(List.of())) {
            if (route == null || isBlank(route.getProductCode())
                    || route.getProcessNames() == null || route.getProcessNames().isEmpty()) {
                continue;
            }
            ApplyAIInitRequest.TypeItem product = products.get(route.getProductCode().trim());
            String finalName = route.getProcessNames().getLast();
            ApplyAIInitRequest.WorkProcessItem process = processes.get(
                    finalName == null ? "" : finalName.trim());
            if (product == null || process == null || isBlank(product.getUnit())) {
                continue;
            }
            if (isBlank(process.getOutputUnit())
                    || unitContractService == null
                    || !unitContractService.areEquivalent(factoryId, product.getUnit(), process.getOutputUnit())) {
                issues.add(new BlueprintSemanticIssue(
                        "FINISHED_OUTPUT_UNIT_MISMATCH", "routes",
                        "产品「" + product.getName() + "」基本单位是 " + product.getUnit()
                                + "，末道工序「" + process.getName() + "」产出单位却是 "
                                + (isBlank(process.getOutputUnit()) ? "空" : process.getOutputUnit()),
                        "把末道工序产出单位改为产品基本单位；箱/托等只放在包装换算中"));
            }
        }

        if (!isBlank(customerSupplied)
                && !"没有".equals(customerSupplied.trim())) {
            issues.add(new BlueprintSemanticIssue(
                    "CUSTOMER_SUPPLIED_RULE_REQUIRED", "contract-material",
                    "访谈确认“" + customerSupplied.trim()
                            + "”，但当前蓝图还没有客供料所有权、来源和只收加工费的结构化规则",
                    "补齐客供料入库来源、客户所有权、领用隔离和计费规则后重新校验"));
        }
        return issues;
    }

    private String conversionProvenance(List<ConversionEvidence> evidence, String fromCode, String toCode) {
        String from = fromCode == null ? "" : fromCode.trim();
        String to = toCode == null ? "" : toCode.trim();
        return Optional.ofNullable(evidence).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .filter(item -> Objects.equals(from, item.getFromCode() == null ? "" : item.getFromCode().trim()))
                .filter(item -> Objects.equals(to, item.getToCode() == null ? "" : item.getToCode().trim()))
                .map(ConversionEvidence::getProvenance)
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .findFirst()
                .orElse("");
    }

    public record BlueprintSemanticIssue(
            String code, String capabilityId, String message, String recovery) { }

    /** work_processes 的 NOT NULL 列 + 两处枚举取自真实库里已有的值, 不新造。 */
    private static final Set<String> WORK_PROCESS_CATEGORIES = Set.of("前处理", "加工", "包装");

    private static final Set<String> WORK_PROCESS_OUTPUT_KINDS = Set.of("SEMI_FINISHED", "FINISHED_GOOD");

    /**
     * 工序校验 —— 全部在写入之前。
     *
     * <p>⛔ 单位不补默认值。{@code work_processes.unit} 是 NOT NULL, 缺了当场拒绝;
     *    编一个「kg」就是替客户编数据, 而用量推算全靠它。
     *
     * <p>⛔ {@code customFields} 的形状交给 {@link WorkProcessCustomFieldSchema} 判 ——
     *    这条规则有三个写入口(Web / AI 工具 / 本处), 在这里再写一份必然漂。
     *    缺 key 的字段会在报工时变成一个名字叫 "null" 的字段, 那时才发现就晚了。
     */
    private void checkWorkProcesses(List<String> problems,
                                    List<ApplyAIInitRequest.WorkProcessItem> items) {
        if (items == null || items.isEmpty()) {
            return;   // 没给工序不算错 —— 老调用方(只写类型/模板)照常
        }
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.WorkProcessItem w = items.get(i);
            String tag = "workProcesses[" + i + "]";
            if (w == null) {
                problems.add(tag + " 是空的");
                continue;
            }
            String name = w.getName() == null ? null : w.getName().trim();
            if (isBlank(name)) {
                problems.add(tag + ".name 为空(process_name 是 NOT NULL, 也是幂等键)");
            } else if (!seenNames.add(name)) {
                problems.add(tag + ".name=" + name + " 在本次请求里重复 —— "
                        + "幂等键是 factoryId+name, 重复会让后一条覆盖前一条而不报错");
            }
            if (isBlank(w.getUnit())) {
                problems.add(tag + ".unit 为空(work_processes.unit 是 NOT NULL) —— "
                        + "⛔ 不补默认值, 用量推算全靠它");
            }
            if (!isBlank(w.getCategory()) && !WORK_PROCESS_CATEGORIES.contains(w.getCategory().trim())) {
                problems.add(tag + ".category=" + w.getCategory()
                        + " 不在库里已有的三档 " + WORK_PROCESS_CATEGORIES);
            }
            if (!isBlank(w.getOutputKind())
                    && !WORK_PROCESS_OUTPUT_KINDS.contains(w.getOutputKind().trim())) {
                problems.add(tag + ".outputKind=" + w.getOutputKind()
                        + " 只能是 " + WORK_PROCESS_OUTPUT_KINDS);
            }
            if (w.getYieldMin() != null && w.getYieldMax() != null
                    && w.getYieldMin().compareTo(w.getYieldMax()) > 0) {
                problems.add(tag + " 出成率下界 " + w.getYieldMin()
                        + " 大于上界 " + w.getYieldMax());
            }
            String schemaProblem = WorkProcessCustomFieldSchema.firstProblemMessage(w.getCustomFields());
            if (schemaProblem != null) {
                problems.add(tag + ".customFields: " + schemaProblem);
            }
            checkRecipeItems(problems, tag + ".auxMaterials", w.getAuxMaterials());
            checkRecipeItems(problems, tag + ".packMaterials", w.getPackMaterials());
        }
    }

    private void checkRecipeItems(List<String> problems, String tag,
                                  List<ApplyAIInitRequest.RecipeItem> items) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.RecipeItem r = items.get(i);
            if (r == null || isBlank(r.getName())) {
                problems.add(tag + "[" + i + "].name 为空(material_name 是 NOT NULL)");
                continue;
            }
            // 用量可以先不填(访谈常常只说"加卤料包"说不出比例), 但填了就必须是正数 ——
            // 0 或负数会让成本按 0 归集, 而那看起来跟"没填"一模一样。
            if (r.getQuantity() != null && r.getQuantity().signum() <= 0) {
                problems.add(tag + "[" + i + "].quantity=" + r.getQuantity()
                        + " 必须为正数(填 0 会让成本静默归集成 0)");
            }
            if (r.getQuantity() != null && isBlank(r.getUnit())) {
                problems.add(tag + "[" + i + "] 填了用量 " + r.getQuantity()
                        + " 却没给单位 —— 无单位的用量没法参与成本核算");
            }
        }
    }

    /**
     * 转换率校验 —— **在写入之前**就把 code 能不能解析成 id 判掉。
     *
     * ⛔ 为什么不能等写入时再解析: material_type_id / product_type_id 都是 NOT NULL,
     *    写到一半才发现某个 code 解析不了, 就成了「配了一半的工厂」。虽然有
     *    @Transactional 兜底回滚, 但用户看到的是一条笼统的 500, 而不是
     *    「哪一条转换率引用了不存在的 code」。
     *
     * 可解析的来源有两处, 缺一不可:
     *   1) 本次请求自带的 productTypes / materialTypes(同一事务里会先写进去)
     *   2) 该工厂库里**已有**的 code(比如上一次 apply 写的)
     * 只查其中一处都会误判 —— 只查请求会漏掉「引用已有品类」的合法情况,
     * 只查库会漏掉「本次一起新建」的合法情况。
     */
    private void checkConversions(List<String> problems,
                                  ApplyAIInitRequest.SuggestedData sd,
                                  String factoryId) {
        List<ApplyAIInitRequest.ConversionItem> items = sd.getConversionRates();
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> productCodes = new HashSet<>();
        Set<String> materialCodes = new HashSet<>();
        if (sd.getProductTypes() != null) {
            for (ApplyAIInitRequest.TypeItem p : sd.getProductTypes()) {
                if (p.getCode() != null) {
                    productCodes.add(p.getCode().trim());
                }
            }
        }
        if (sd.getMaterialTypes() != null) {
            for (ApplyAIInitRequest.TypeItem m : sd.getMaterialTypes()) {
                if (m.getCode() != null) {
                    materialCodes.add(m.getCode().trim());
                }
            }
        }

        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.ConversionItem c = items.get(i);
            String tag = "conversionRates[" + i + "]";
            String from = c.getFromCode() == null ? "" : c.getFromCode().trim();
            String to = c.getToCode() == null ? "" : c.getToCode().trim();

            if (from.isEmpty()) {
                problems.add(tag + ".fromCode 为空");
            } else if (!materialCodes.contains(from)
                    && rawMaterialTypeRepository.findByFactoryIdAndCode(factoryId, from).isEmpty()) {
                problems.add(tag + ".fromCode=" + from + " 在本次 materialTypes 和库里都找不到");
            }

            if (to.isEmpty()) {
                problems.add(tag + ".toCode 为空");
            } else if (!productCodes.contains(to)
                    && productTypeRepository.findByFactoryIdAndCode(factoryId, to).isEmpty()) {
                problems.add(tag + ".toCode=" + to + " 在本次 productTypes 和库里都找不到");
            }

            // ⛔ 出成率必须为正: 0 或负数落进 conversion_rate 会让下游的用量推算
            //    算出 0 或负的原料需求 —— 而那是个看起来很正常的数字。
            if (c.getRate() == null) {
                problems.add(tag + ".rate 为空");
            } else if (c.getRate().compareTo(BigDecimal.ZERO) <= 0) {
                problems.add(tag + ".rate=" + c.getRate() + " 必须为正数");
            }
        }
    }

    private void checkTypeItems(List<String> problems, String label,
                                List<ApplyAIInitRequest.TypeItem> items) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.TypeItem it = items.get(i);
            if (isBlank(it.getCode())) {
                problems.add(label + "[" + i + "].code 为空");
            }
            if (isBlank(it.getName())) {
                problems.add(label + "[" + i + "].name 为空");
            }
            if (isBlank(it.getUnit())) {
                problems.add(label + "[" + i + "].unit 为空 (该列 NOT NULL, 不允许留空或代填)");
            }
        }
    }

    /**
     * 包装规格校验 —— 2026-08-12 新增。
     *
     * <p>⚠️ 在这之前 {@code TypeItem.boxConversionCoefficient} 的注释写着
     * 「必须 &gt; 0, 否则整对丢弃(见 checkPackaging)」, 而 <b>checkPackaging 全仓不存在</b>,
     * 也没有任何一处做这件事 —— 注释描述的是一个想象出来的守卫。
     *
     * <p>⛔ 补的是<b>拒绝</b>而不是注释里写的「丢弃」。静默丢弃正是这一轮抓到的缺陷形状:
     * 载荷里写着 level1Unit=「大袋」/ 系数 25, 落库 200 全绿、product_types 上两列也在,
     * 唯独换算图里没有这条边, 换算接口回 {@code UNKNOWN_UNIT} —— 配了等于没配, 而没有
     * 任何一层说过话。这个文件自己的判据是「全量校验先于任何写入」, 照它办。
     *
     * <p>校验在 apply 与 preview 共用, 所以客户点确认<b>之前</b>就能看见。
     */
    private void checkPackaging(List<String> problems, String factoryId,
                                List<ApplyAIInitRequest.TypeItem> items) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.TypeItem it = items.get(i);
            if (it == null) {
                continue;
            }
            String tag = "productTypes[" + i + "]";
            boolean hasLevel1 = !isBlank(it.getLevel1Unit());
            BigDecimal coefficient = it.getBoxConversionCoefficient();
            if (!hasLevel1 && coefficient == null) {
                continue;   // 不填包装规格是合法的 —— 不是每个 SKU 都按箱卖
            }
            if (!hasLevel1) {
                problems.add(tag + " 给了 boxConversionCoefficient=" + coefficient
                        + " 却没给 level1Unit —— 「一箱装多少」里的「箱」是什么单位说不出来");
                continue;
            }
            if (coefficient == null) {
                problems.add(tag + ".level1Unit=" + it.getLevel1Unit().trim()
                        + " 缺 boxConversionCoefficient —— 只知道按它卖、不知道一"
                        + it.getLevel1Unit().trim() + "装多少, 换算边建不出来");
                continue;
            }
            if (coefficient.signum() <= 0) {
                problems.add(tag + ".boxConversionCoefficient=" + coefficient
                        + " 必须 > 0(它是换算因子, 库里那条 CHECK 也只收正数)");
                continue;
            }
            UnitNormalizationResult level1 = unitContractService.normalize(
                    factoryId, it.getLevel1Unit());
            if (!level1.recognized()) {
                problems.add(tag + ".level1Unit=「" + it.getLevel1Unit().trim()
                        + "」不在本厂单位表里 —— 这样落库会**静默丢掉**包装换算"
                        + "(产品上留着规格, 换算图里没有这条边, 换算接口回「存在未知单位」)。"
                        + "请改用单位表里的写法, 或先在单位字典里加这个单位");
                continue;
            }
            UnitNormalizationResult base = unitContractService.normalize(factoryId, it.getUnit());
            if (!base.recognized()) {
                problems.add(tag + ".unit=「" + (it.getUnit() == null ? "" : it.getUnit().trim())
                        + "」不在本厂单位表里, 但填了包装规格 —— 换算边的另一端落不了地");
                continue;
            }
            if (base.code().equals(level1.code())) {
                problems.add(tag + " 的 level1Unit「" + it.getLevel1Unit().trim()
                        + "」与 unit「" + it.getUnit().trim() + "」归一后是同一个单位("
                        + base.code() + ") —— 「1 " + base.code() + " = " + coefficient
                        + " " + base.code() + "」不成立");
            }
        }
    }

    /**
     * 得率口径闸 —— <b>0~1 还是 0~100 搞反就是 100 倍</b>, 而且悄无声息。
     *
     * <h3>为什么必须拦</h3>
     *
     * 访谈里用户说的是「得率98%」, LLM 抽出来的是字符串 {@code "98%"};
     * 到了 suggestedData 要变成 BigDecimal。转成 {@code 0.98} 是对的,
     * 转成 {@code 98} 就是 <b>9800%</b> —— 而 apply 原先是
     * {@code wp.setStandardYieldMin(w.getYieldMin())} <b>直通落库, 零校验</b>。
     *
     * <p>错了不会报错, 只会让这道工序的成本/投入产出全部差两个数量级,
     * 要到有人对账才发现。
     *
     * <h3>阈值不是拍的</h3>
     *
     * 2026-08-11 实测库里 44 条真实得率: <b>全部落在 0.70 ~ 1.55</b>,
     * &gt;10 的一条都没有。
     *
     * <p>⛔ 不能简单地「&gt;1 就拒」—— 滚揉(保水)这类工序真的会 &gt;1(实测 1.30/1.42/1.55),
     * 那是加水增重, 不是错。所以判的是<b>数量级</b>(&gt;10 必错), 不是「超过 1」。
     *
     * <p>⚠️ 1~10 之间不拦但提示: 那个区间既可能是真的保水工序, 也可能是把 1.5 写成了 15。
     * 拦下来会误伤真实数据, 沉默又等于放过 —— 所以给一条能看见的提醒。
     */
    private void checkYieldScale(List<String> problems,
                                 List<ApplyAIInitRequest.WorkProcessItem> items) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.WorkProcessItem w = items.get(i);
            if (w == null) {
                continue;
            }
            String tag = "workProcesses[" + i + "](" + (w.getName() == null ? "?" : w.getName()) + ")";
            checkOneYield(problems, tag + ".yieldMin", w.getYieldMin());
            checkOneYield(problems, tag + ".yieldMax", w.getYieldMax());
            // ⚠️ 上下限颠倒**不在这里查** —— checkWorkProcesses 已经有一条
            //    「出成率下界 X 大于上界 Y」。我第一版又加了一条, 阴性对照时同一个错误
            //    报了两遍才发现。⛔ 加校验前先搜一遍现有的, 否则用户看到重复报错。
        }
    }

    private void checkOneYield(List<String> problems, String tag, BigDecimal v) {
        if (v == null) {
            return;
        }
        if (v.signum() <= 0) {
            problems.add(tag + "=" + v.toPlainString() + " 必须大于 0");
            return;
        }
        if (v.compareTo(new BigDecimal("10")) > 0) {
            problems.add(tag + "=" + v.toPlainString()
                    + " 数量级不对 —— 得率的口径是 0~1(实测库里 44 条全在 0.70~1.55), "
                    + "98% 应该写 0.98 而不是 98");
        }
    }

    /**
     * 模块配置的校验。
     *
     * ⛔ moduleId 必须在目录里: RN 那边是 {@code modules[moduleId]} 直接查表,
     *    查不到就走 {@code mod ? mod.enabled : true} 的兜底 —— **不报错, 只是永远不生效**。
     *    与盯指标同一个形状, 所以同样必须在落库前拦。
     */
    private void checkModuleConfigs(List<String> problems,
                                    List<ApplyAIInitRequest.ModuleConfigItem> items) {
        if (items == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.ModuleConfigItem m = items.get(i);
            String tag = "moduleConfigs[" + i + "]";
            if (m == null || isBlank(m.getModuleId())) {
                problems.add(tag + ".moduleId 为空");
                continue;
            }
            String id = m.getModuleId().trim().toLowerCase();
            InterviewModuleConfigCatalog.Entry e = InterviewModuleConfigCatalog.find(id);
            if (e == null) {
                problems.add(tag + ".moduleId=" + m.getModuleId().trim()
                        + " 不是可配置的模块(前端只认: "
                        + InterviewModuleConfigCatalog.legalIds() + ")");
                continue;
            }
            if (!seen.add(id)) {
                problems.add(tag + ".moduleId=" + id + " 在本次请求里重复");
            }
        }
    }

    /**
     * 盯指标的校验。
     *
     * ⛔ 指标名必须在目录里: 评估器对不认识的名字只记一行 warn 然后**静默跳过** ——
     *    配置看着写进去了, 实际一次都不会评估。
     */
    private void checkMetricWatches(List<String> problems,
                                    List<ApplyAIInitRequest.MetricWatchItem> items) {
        if (items == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.MetricWatchItem w = items.get(i);
            String tag = "metricWatches[" + i + "]";
            if (w == null || isBlank(w.getMetricName())) {
                problems.add(tag + ".metricName 为空");
                continue;
            }
            String name = w.getMetricName().trim();
            InterviewAnomalyMetricCatalog.Entry e = InterviewAnomalyMetricCatalog.find(name);
            if (e == null) {
                problems.add(tag + ".metricName=" + name + " 不是可盯的指标"
                        + "(评估器只认: " + InterviewAnomalyMetricCatalog.all().stream()
                                .map(InterviewAnomalyMetricCatalog.Entry::metricName)
                                .collect(Collectors.joining("/")) + ")");
                continue;
            }
            if (!seen.add(name)) {
                problems.add(tag + ".metricName=" + name + " 在本次请求里重复");
            }
            if (Boolean.FALSE.equals(w.getEnabled())) {
                continue;
            }
            if (w.getThreshold() == null) {
                problems.add(tag + " " + e.label() + " 必须填阈值 —— 没有阈值的「盯着」是空转");
            }
        }
    }

    /**
     * 告警选项校验。
     *
     * ⛔ 金额类必须带阈值: {@code evaluateRule} 在 SpEL 为空时直接返回 true,
     *    而 PO/SO 的 listener 内置逻辑里<b>没有金额比较</b> —— 不带阈值就开,
     *    等于每张采购/销售单都收一条「金额超过预警阈值」。
     *    这是「配置看着开了、行为完全不是那回事」, 比不开更糟。
     */
    private void checkAlerts(List<String> problems,
                             List<ApplyAIInitRequest.AlertItem> items) {
        if (items == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.AlertItem a = items.get(i);
            String tag = "alerts[" + i + "]";
            if (a == null || isBlank(a.getAlertType())) {
                problems.add(tag + ".alertType 为空");
                continue;
            }
            String typeName = a.getAlertType().trim();
            InterviewAlertCatalog.Entry entry = InterviewAlertCatalog.find(typeName);
            if (entry == null) {
                problems.add(tag + ".alertType=" + typeName + " 不是建厂可选的告警类型"
                        + "(可选: " + InterviewAlertCatalog.all().stream()
                                .map(e -> e.type().name()).collect(Collectors.joining("/")) + ")");
                continue;
            }
            if (!seen.add(typeName)) {
                problems.add(tag + ".alertType=" + typeName + " 在本次请求里重复");
            }
            if (Boolean.FALSE.equals(a.getEnabled())) {
                continue;
            }
            if (entry.needsAmount()) {
                if (a.getAmountThreshold() == null) {
                    problems.add(tag + " " + entry.label() + " 必须填金额阈值 —— "
                            + "不填就开会让每一张单子都触发告警(内置逻辑里没有金额比较)");
                } else if (a.getAmountThreshold().signum() <= 0) {
                    problems.add(tag + ".amountThreshold 必须大于 0, 收到 "
                            + a.getAmountThreshold().toPlainString());
                }
            } else if (a.getAmountThreshold() != null) {
                problems.add(tag + " " + entry.label() + " 不吃金额阈值 —— "
                        + "填了也不会生效, 别让人以为设上了");
            }
        }
    }

    /**
     * 编码分段校验。
     *
     * ⛔ 段码必须是纯数字且长度一致 —— 这套编码是**按位切**的(L1 3 位 / L2 6 位 / L3 10 位,
     *    F006 实测), 混进字母或长度不齐, 后面按前缀找父级就再也对不上, 而且不会报错,
     *    只会查不到。
     */
    private void checkCodeSegments(List<String> problems,
                                   List<ApplyAIInitRequest.CodeSegmentItem> items) {
        if (items == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.CodeSegmentItem c = items.get(i);
            String tag = "codeSegments[" + i + "]";
            if (c == null || isBlank(c.getCode())) {
                problems.add(tag + ".code 为空");
                continue;
            }
            String code = c.getCode().trim();
            if (!code.matches("\\d{3}")) {
                problems.add(tag + ".code=" + code + " 必须是 3 位数字(L1 段码, F006 实测 001/002/003)");
            }
            if (!seen.add(code)) {
                problems.add(tag + ".code=" + code + " 在本次请求里重复");
            }
            if (isBlank(c.getLabel())) {
                problems.add(tag + ".label 为空(segment_label 非空)");
            }
        }
    }

    /** 合法仓库类型 —— 从枚举反射取, 不手抄(手抄的清单只会改到正在看的那一处)。 */
    private static final Set<String> WAREHOUSE_TYPES =
            java.util.Arrays.stream(FactoryWarehouse.WarehouseType.values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private void checkWarehouses(List<String> problems, List<ApplyAIInitRequest.WarehouseItem> items) {
        if (items == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.WarehouseItem w = items.get(i);
            String tag = "warehouses[" + i + "]";
            if (w == null || isBlank(w.getCode())) {
                problems.add(tag + ".code 为空(工厂内唯一编号, 也是幂等键)");
                continue;
            }
            if (!seen.add(w.getCode().trim())) {
                problems.add(tag + ".code=" + w.getCode() + " 在本次请求里重复");
            }
            if (isBlank(w.getName())) {
                problems.add(tag + ".name 为空(factory_warehouses.name 是 NOT NULL)");
            }
            if (isBlank(w.getType()) || !WAREHOUSE_TYPES.contains(w.getType().trim())) {
                problems.add(tag + ".type=" + w.getType() + " 不是合法仓库类型, 可选 "
                        + WAREHOUSE_TYPES.stream().sorted().toList());
            }
        }
    }

    /**
     * 产品×工序绑定校验。
     *
     * <p>⛔ 产品 code 与工序名都必须**当场解析得到**(本次请求里的, 或库里已有的) ——
     *    解析不了就整体拒绝。这两个 id 在 product_work_processes 里都是 NOT NULL,
     *    到写入时才发现解析不了, 那时已经写了半张表。
     */
    private void checkProductProcesses(List<String> problems,
                                       ApplyAIInitRequest.SuggestedData sd,
                                       String factoryId) {
        if (sd == null || sd.getProductProcesses() == null) {
            return;
        }
        Set<String> inRequestProducts = new HashSet<>();
        if (sd.getProductTypes() != null) {
            for (ApplyAIInitRequest.TypeItem t : sd.getProductTypes()) {
                if (t != null && !isBlank(t.getCode())) {
                    inRequestProducts.add(t.getCode().trim());
                }
            }
        }
        Set<String> inRequestProcesses = new HashSet<>();
        if (sd.getWorkProcesses() != null) {
            for (ApplyAIInitRequest.WorkProcessItem w : sd.getWorkProcesses()) {
                if (w != null && !isBlank(w.getName())) {
                    inRequestProcesses.add(w.getName().trim());
                }
            }
        }
        for (int i = 0; i < sd.getProductProcesses().size(); i++) {
            ApplyAIInitRequest.ProductProcessItem pp = sd.getProductProcesses().get(i);
            String tag = "productProcesses[" + i + "]";
            if (pp == null || isBlank(pp.getProductCode())) {
                problems.add(tag + ".productCode 为空");
                continue;
            }
            String code = pp.getProductCode().trim();
            if (!inRequestProducts.contains(code)
                    && productTypeRepository.findByFactoryIdAndCode(factoryId, code).isEmpty()) {
                problems.add(tag + ".productCode=" + code + " 在本次 productTypes 和库里都找不到");
            }
            if (pp.getProcessNames() == null || pp.getProcessNames().isEmpty()) {
                problems.add(tag + " 没给工序 —— 一个产品总要走至少一道工序");
                continue;
            }
            Set<String> dup = new HashSet<>();
            for (String name : pp.getProcessNames()) {
                if (isBlank(name)) {
                    problems.add(tag + " 的工序名里有空值");
                    continue;
                }
                String pn = name.trim();
                if (!dup.add(pn)) {
                    problems.add(tag + " 里工序「" + pn + "」重复 —— "
                            + "同一产品同一道工序出现两次, process_order 会打架");
                }
                if (!inRequestProcesses.contains(pn)
                        && workProcessRepository.findByFactoryIdAndProcessName(factoryId, pn).isEmpty()) {
                    problems.add(tag + " 的工序「" + pn + "」在本次 workProcesses 和库里都找不到");
                }
            }
        }
    }

    /** factory_settings 上真实存在的开关字段 —— 反射取, 不手抄。 */
    private static final Set<String> MODULE_SWITCH_FIELDS =
            java.util.Arrays.stream(FactorySettings.class.getDeclaredFields())
                    .filter(f -> f.getName().startsWith("enable")
                            && (f.getType() == Boolean.class || f.getType() == boolean.class))
                    .map(java.lang.reflect.Field::getName)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 模块开关校验。
     *
     * ⛔ 不认识的 key 直接拒绝, 不静默忽略 —— 静默忽略的表现是
     *    「客户明明关了考勤, 保存后还开着」, 而每一层都返回 200。
     */
    private void checkModules(List<String> problems, Map<String, Boolean> modules) {
        if (modules == null) {
            return;
        }
        for (Map.Entry<String, Boolean> e : modules.entrySet()) {
            if (!MODULE_SWITCH_FIELDS.contains(e.getKey())) {
                problems.add("modules." + e.getKey() + " 不是 factory_settings 上的开关字段, "
                        + "可用的是 " + MODULE_SWITCH_FIELDS.stream().sorted().toList());
            } else if (e.getValue() == null) {
                problems.add("modules." + e.getKey() + " 的值为 null —— "
                        + "开还是关要说清楚, null 会被当成「没提」而保持原样");
            }
        }
    }

    /**
     * 阈值校验。合法集合与归类走 {@link ThresholdKeyCatalog}(与 AI 工具同一份) ——
     * 各写一份的后果是同一个 key 在一条路上合法、在另一条路上 400。
     */
    private void checkThresholds(List<String> problems, String factoryId, List<ApplyAIInitRequest.ThresholdItem> items) {
        if (items == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ApplyAIInitRequest.ThresholdItem t = items.get(i);
            String tag = "thresholds[" + i + "]";
            if (t == null || isBlank(t.getKey())) {
                problems.add(tag + ".key 为空");
                continue;
            }
            String key = t.getKey().trim();
            if (!ThresholdKeyCatalog.isKnown(key)) {
                problems.add(tag + ".key=" + key + " 不是已登记的标准 —— "
                        + "⛔ 不新建未登记的 key, 那样的阈值没有任何代码会去读");
                continue;
            }
            if (!seen.add(key)) {
                problems.add(tag + ".key=" + key + " 在本次请求里重复");
            }
            // ⛔ 空值不当成「保持默认」: 实体里 asDouble() 对空值抛 IllegalStateException,
            //    存进去等于埋一颗运行时才炸的雷。
            if (isBlank(t.getValue())) {
                problems.add(tag + "(" + key + ") 的 value 为空 —— "
                        + "不想改就别提交这一条, 空值会让读取方在运行时炸");
            } else {
                try {
                    assemblyThresholdWriter.validate(factoryId, key, t.getValue().trim());
                } catch (com.cretas.aims.exception.BusinessException invalid) {
                    problems.add(tag + ": " + invalid.getMessage());
                }
            }
        }
    }

    /**
     * 从 workProcesses 的<b>结构位置</b>推出每个物料的角色 —— 不问用户, 也不问 LLM。
     *
     * <p>⛔ 同一个名字同时出现在辅料和包材里就是<b>冲突</b>, 必须报错而不是挑一个:
     * 挑错了下游 5 条包材规则会整套用错(要不要维护单价/要不要清 storageType/…)。
     *
     * @return 物料名 → 角色标签; 冲突的名字放进 conflicts
     */
    public Map<String, String> deriveMaterialRoles(ApplyAIInitRequest.SuggestedData sd,
                                                    Set<String> conflicts) {
        Map<String, String> roles = new LinkedHashMap<>();
        if (sd == null || sd.getWorkProcesses() == null) {
            return roles;
        }
        for (ApplyAIInitRequest.WorkProcessItem w : sd.getWorkProcesses()) {
            collectRole(roles, conflicts, w.getAuxMaterials(), "辅料");
            collectRole(roles, conflicts, w.getPackMaterials(), "包材");
        }
        return roles;
    }

    private void collectRole(Map<String, String> roles, Set<String> conflicts,
                             List<ApplyAIInitRequest.RecipeItem> items, String role) {
        if (items == null) {
            return;
        }
        for (ApplyAIInitRequest.RecipeItem it : items) {
            if (it == null || isBlank(it.getName())) {
                continue;
            }
            String name = it.getName().trim();
            String had = roles.put(name, role);
            if (had != null && !had.equals(role)) {
                conflicts.add(name);
            }
        }
    }

    /**
     * 角色标签 → 这家厂<b>自己建的</b> L1 分段。
     *
     * <p>⚠️ 分类体系由用户自己定(2026-08-11 拍板), 所以不能假设「原料/辅料/包材」这三类一定存在。
     * 对不上就返回 null = 未分类, 由预览如实显示, ⛔ 不许硬塞一个。
     */
    private String resolveL1Label(String factoryId, String roleLabel,
                                  List<ApplyAIInitRequest.CodeSegmentItem> samePlanSegments) {
        if (roleLabel == null) {
            return null;
        }
        String samePlan = Optional.ofNullable(samePlanSegments).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .map(ApplyAIInitRequest.CodeSegmentItem::getLabel)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(label -> label.equalsIgnoreCase(roleLabel.trim()))
                .findFirst()
                .orElse(null);
        if (samePlan != null) {
            return samePlan;
        }
        return codeSegmentRepository
                .findByFactoryIdAndLevelOrderBySortOrderAscIdAsc(factoryId, (short) 1)
                .stream()
                .map(MaterialCodeSegment::getSegmentLabel)
                .filter(lbl -> lbl != null && lbl.trim().equalsIgnoreCase(roleLabel.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 物料的最终分类: 载荷显式给的优先, 其次按结构位置推, 都没有就当原料。 */
    public String classifyMaterial(String factoryId, ApplyAIInitRequest.TypeItem m,
                                    Map<String, String> roles) {
        return classifyMaterial(factoryId, m, roles, List.of());
    }

    /** 同一草稿本轮将创建的 L1 也是合法引用，不得只查落库前的旧表。 */
    public String classifyMaterial(String factoryId, ApplyAIInitRequest.TypeItem m,
                                   Map<String, String> roles,
                                   List<ApplyAIInitRequest.CodeSegmentItem> samePlanSegments) {
        String wanted = !isBlank(m.getCategory()) ? m.getCategory().trim()
                : roles.getOrDefault(m.getName() == null ? "" : m.getName().trim(), "原料");
        return resolveL1Label(factoryId, wanted, samePlanSegments);
    }

    /** 与 Python 侧 _FACTORY_INIT_ENTITY_TYPES 必须一致。 */
    private static final Set<String> ALLOWED_ENTITY_TYPES = Set.of(
            "MATERIAL_BATCH", "PROCESSING_BATCH", "QUALITY_CHECK", "SHIPMENT");

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
