package com.cretas.aims.blueprint.interview;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.ConversionEvidence;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AlertThresholdRepository;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.FormTemplateRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.factory.FactoryWarehouseService;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 「确认之后会发生什么」—— 预览端点与落库端点<b>共用这一个</b>计算。
 *
 * <h2>⛔ 为什么必须共用</h2>
 *
 * 预览与执行各写一份, 必然漂成「预览说能改, 确认时才报错」或者更糟的
 * 「预览说新建 2 项, 实际写入 0 项」—— 后者本仓已经发生过一次。
 * 抽成独立 service 之后这条约束更硬: 全仓只有一个 {@code planChanges}。
 *
 * <p>⚠️ 新增落库类型时, 不在这里登记就等于没给客户看过。
 *
 * <h2>这次搬迁不改行为</h2>
 *
 * 原文逐字搬运。唯一的机械改动: 原先同类内的 {@code deriveMaterialRoles} /
 * {@code classifyMaterial} 调用改成经注入的 {@link FactoryBuildApplyValidator} ——
 * 仍是<b>同一份实现</b>, 不是复制一份。
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class FactoryBuildPlanService {

    @Autowired
    private FormTemplateRepository formTemplateRepository;

    /** 预览要按与 apply **同一个**幂等键判「关键控制点已存在」—— 见那一段的注释。 */
    @Autowired
    private com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository haccpCheckpointRepository;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private ConversionRepository conversionRepository;

    @Autowired
    private WorkProcessRepository workProcessRepository;

    @Autowired
    private FactoryWarehouseService factoryWarehouseService;

    @Autowired
    private ProductWorkProcessRepository productWorkProcessRepository;

    @Autowired
    private FactorySettingsRepository factorySettingsRepository;

    @Autowired
    private AlertThresholdRepository alertThresholdRepository;

    @Autowired
    private FactoryFeatureConfigRepository featureConfigRepository;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private MaterialCodeSegmentRepository codeSegmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FactoryThresholdRepository thresholdRepository;

    /** 预览「停用哪些指标」时要拿名字, 与落库查的是同一个方法。 */
    @Autowired
    private com.cretas.aims.repository.indicator.IndicatorRepository indicatorRepository;

    /** ⛔ 物料分类与校验共用同一份 —— 预览显示的「未分类」必须和落库判的是同一件事。 */
    @Autowired
    private FactoryBuildApplyValidator validator;


    /**
     * 预览: 只算不写。
     *
     * <p>先跑与落库<b>同一份</b>校验 —— 不合规的在这一步就 400,
     * 而不是等客户点了确认才报错(那正是预览要消灭的东西)。
     *
     * <p>⚠️ 这里的 400 文案与 apply 的<b>不完全一样</b>(apply 多一句
     * {@code 请修正后重新提交} 的 hint)。拆分时逐字保留 ——
     * 「顺手统一一下措辞」在这个 commit 里就是行为改动。
     */
    public List<PlannedChange> preview(String factoryId, ApplyAIInitRequest request) {
        return preview(factoryId, request, List.of());
    }

    public List<PlannedChange> preview(String factoryId, ApplyAIInitRequest request,
                                       List<ConversionEvidence> conversionEvidence) {
        List<String> problems = validator.validateApplyRequest(request, factoryId);
        if (!problems.isEmpty()) {
            throw new BusinessException(400,
                    "配置不合规, 未写入任何内容 (" + problems.size() + " 处): "
                    + String.join("; ", problems)).withSeverity("error");
        }
        return planChanges(factoryId, request, Boolean.TRUE.equals(request.getOverwrite()), conversionEvidence);
    }

    public List<FactoryBuildApplyValidator.BlueprintSemanticIssue> blueprintSemanticIssues(
            String factoryId, ApplyAIInitRequest request, String customerSupplied,
            List<ConversionEvidence> conversionEvidence) {
        return validator.validateBlueprintSemantics(request, factoryId, customerSupplied, conversionEvidence);
    }

    /**
     * 算出「确认之后会发生什么」。
     *
     * <h3>⛔ 为什么预览必须和落库共用这一个方法</h3>
     *
     * 预览与执行各写一份, 必然漂成「预览说能改, 确认时才报错」或者更糟的
     * 「预览说新建 2 项, 实际写入 0 项」—— 后者本仓已经发生过一次
     * (转换率按 (原料,成品) 幂等, 而预览那边写死 exists=false)。
     * 那是防呆 Rule 1「预先显示边界」的实打实违反: 客户按预览点的确认,
     * 系统做的却是另一件事。
     *
     * <p>更要紧的是<b>漏掉整类</b>: 2026-08-11 之前预览只列
     * 模板/产品/原料/转换率四类, 而落库那边已经会写工序、辅料包材配方、存放区、
     * 产品工序链、模块开关、预警阈值 —— <b>客户确认的东西里有一半没给他看过</b>。
     * 所以这里逐类都要出现; 新增落库类型时, 不在这里登记就等于没给客户看。
     */
    public List<PlannedChange> planChanges(String factoryId, ApplyAIInitRequest request,
                                             boolean overwrite) {
        return planChanges(factoryId, request, overwrite, List.of());
    }

    public List<PlannedChange> planChanges(String factoryId, ApplyAIInitRequest request,
                                            boolean overwrite,
                                            List<ConversionEvidence> conversionEvidence) {
        FactoryAssemblyDraftIntent.requireValid(request.getDraftCapabilityIntents());
        List<PlannedChange> out = new ArrayList<>();
        ApplyAIInitRequest.SuggestedData sd = request.getSuggestedData();

        if (request.getSchemas() != null) {
            for (ApplyAIInitRequest.SchemaItem it : request.getSchemas()) {
                // ⛔ 用与落库**同一个**查询方法(findActive…): 换一个就等于两处口径,
                //    而这个方法正是 apply 判断跳过/覆盖时用的那个。
                boolean exists = formTemplateRepository
                        .findActiveByFactoryIdAndEntityType(factoryId, it.getEntityType())
                        .isPresent();
                out.add(change("表单模板", it.getEntityType(),
                        it.getEntityName() + " · " + (it.getFields() == null ? 0 : it.getFields().size()) + " 个字段",
                        exists, overwrite));
            }
        }
        if (sd == null) {
            appendDraftIntentChanges(out, request.getDraftCapabilityIntents());
            return out;
        }
        if (sd.getProductTypes() != null) {
            for (ApplyAIInitRequest.TypeItem t : sd.getProductTypes()) {
                boolean exists = productTypeRepository.findByFactoryIdAndCode(factoryId, t.getCode()).isPresent();
                // ⛔ 包装规格必须显示在预览里, 与「未分类物料」同一条判据: 它会改换算图,
                //    而换算图错了是 N 倍的错(1 箱当成 0.05 斤)。人在这里能看见, 才有机会改。
                String detail = t.getName() + " / " + t.getUnit();
                if (!isBlank(t.getLevel1Unit()) && t.getBoxConversionCoefficient() != null) {
                    detail += " / 包装 1" + t.getLevel1Unit().trim() + " = "
                            + t.getBoxConversionCoefficient().stripTrailingZeros().toPlainString()
                            + t.getUnit();
                }
                out.add(change("产品类型", t.getCode(), detail, exists, overwrite));
            }
        }
        if (sd.getMaterialTypes() != null) {
            Map<String, String> previewRoles = validator.deriveMaterialRoles(sd, new HashSet<>());
            for (ApplyAIInitRequest.TypeItem t : sd.getMaterialTypes()) {
                boolean exists = rawMaterialTypeRepository.findByFactoryIdAndCode(factoryId, t.getCode()).isPresent();
                // ⛔ 分类必须显示在预览里。判定不出时如实写「未分类」而不是留空 ——
                //    未分类的物料 category/primary_code 为空, 而下游全是静默失败
                //    (库存台账按类别筛选时它们一条都不出现)。人在这里能看见, 才有机会改。
                String l1 = exists ? null : validator.classifyMaterial(
                        factoryId, t, previewRoles, sd.getCodeSegments());
                String detail = t.getName() + " / " + t.getUnit()
                        + (exists ? "" : " / " + (l1 != null ? "分类 " + l1
                                : "⚠️ 未分类(本厂 L1 里没有对应的类, 落库后按类别筛选看不到它)"));
                if (t.getStorageType() != null) detail += " / 储存 " + FactoryBuildApplyValidator.materialStorageLabel(t.getStorageType());
                out.add(change("原料类型", t.getCode(), detail, exists, overwrite));
            }
        }
        if (sd.getConversionRates() != null) {
            for (ApplyAIInitRequest.ConversionItem c : sd.getConversionRates()) {
                String from = c.getFromCode() == null ? "" : c.getFromCode().trim();
                String to = c.getToCode() == null ? "" : c.getToCode().trim();
                boolean exists = rawMaterialTypeRepository.findByFactoryIdAndCode(factoryId, from)
                        .flatMap(m -> productTypeRepository.findByFactoryIdAndCode(factoryId, to)
                                .flatMap(pt -> conversionRepository
                                        .findByFactoryIdAndMaterialTypeIdAndProductTypeId(
                                                factoryId, m.getId(), pt.getId())))
                        .isPresent();
                // ⛔ detail 里要带**中文名**: 键是机器码(`RM_CHICKEN→FD_CHICKEN`),
                //    而客户说的是「冷冻鸡胸肉」「冻干鸡胸粒」。owner 2026-08-17 定的硬要求:
                //    建厂过程里客户看到的文字不许出现程序员的话。
                // ⚠️ 键**不能**改成中文 —— 预览与回执按它对账(conversionKey 全仓唯一一处定义,
                //    parity 闸逐条比的就是它)。⇒ 键留给机器, detail 给人看。
                //    名字查的是**本次载荷自己给的**两份清单, 不是另查一遍库(那会是第二个口径)。
                PlannedChange conversion = change("转换率", conversionKey(from, to),
                        nameOfCode(sd.getMaterialTypes(), from) + " → "
                                + nameOfCode(sd.getProductTypes(), to)
                                + "，出成率 " + c.getRate(), exists, overwrite);
                if ("AI_INFERRED".equalsIgnoreCase(conversionProvenance(conversionEvidence, from, to))) {
                    conversion.setAction("待确认");
                    conversion.setDetail(conversion.getDetail() + "；来源为 Agent 推演，禁止直接应用");
                }
                out.add(conversion);
            }
        }
        if (sd.getWorkProcesses() != null) {
            for (ApplyAIInitRequest.WorkProcessItem w : sd.getWorkProcesses()) {
                String name = w.getName() == null ? "" : w.getName().trim();
                boolean exists = !workProcessRepository.findByFactoryIdAndProcessName(factoryId, name).isEmpty();
                StringBuilder d = new StringBuilder();
                d.append(w.getCategory() == null ? "" : w.getCategory()).append(' ')
                 .append(w.getUnit()).append("→").append(w.getOutputUnit() == null ? w.getUnit() : w.getOutputUnit());
                if (w.getYieldMin() != null || w.getYieldMax() != null) {
                    d.append(" 出成 ").append(w.getYieldMin()).append('–').append(w.getYieldMax());
                }
                int aux = w.getAuxMaterials() == null ? 0 : w.getAuxMaterials().size();
                int pack = w.getPackMaterials() == null ? 0 : w.getPackMaterials().size();
                if (aux + pack > 0) {
                    d.append(" · 辅料 ").append(aux).append(" 包材 ").append(pack);
                }
                out.add(change("工序", name, d.toString().trim(), exists, overwrite));
            }
        }
        if (sd.getWarehouses() != null) {
            for (ApplyAIInitRequest.WarehouseItem w : sd.getWarehouses()) {
                String code = w.getCode() == null ? "" : w.getCode().trim();
                boolean exists = factoryWarehouseService.findByCode(factoryId, code).isPresent();
                // ⚠️ 存放区落库那边**不覆盖**(已存在就跳过), 所以这里也不许显示「覆盖」——
                //    预览承诺覆盖而实际跳过, 同样是预览与执行不一致。
                // ⛔ 不再把仓库类型的**枚举名**(FINISHED / RAW / WIP…)拼进 detail:
                //    客户读到的是「成品仓 / FINISHED」, 后半截是给机器看的。
                //    名字本身已经说清是什么仓; 类型在仓库维护页上看得到。
                // ⚠️ 这里不做「枚举 → 中文」的映射: 那份映射前端已经有一份
                //    (WAREHOUSE_TYPE_LABELS), 后端再写一份就是同一个东西两份, 必漂。
                PlannedChange c = change("存放区", code, w.getName(), exists, false);
                out.add(c);
            }
        }
        if (sd.getProductProcesses() != null) {
            for (ApplyAIInitRequest.ProductProcessItem pp : sd.getProductProcesses()) {
                String code = pp.getProductCode() == null ? "" : pp.getProductCode().trim();
                int cnt = pp.getProcessNames() == null ? 0 : pp.getProcessNames().size();
                // 产品工序链是**整条替换**, 所以库里有就是「覆盖」, 与落库口径一致
                boolean exists = productTypeRepository.findByFactoryIdAndCode(factoryId, code)
                        .map(pt -> !productWorkProcessRepository
                                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, pt.getId())
                                .isEmpty())
                        .orElse(false);
                PlannedChange c = new PlannedChange();
                c.setGroup("产品工序链");
                c.setKey(code);
                c.setDetail(cnt + " 道: " + String.join("→",
                        pp.getProcessNames() == null ? List.of() : pp.getProcessNames()));
                c.setExists(exists);
                c.setAction(exists ? "覆盖" : "新建");   // 整条替换, 不存在"跳过"
                out.add(c);
            }
        }
        if (sd.getModules() != null) {
            for (Map.Entry<String, Boolean> e : sd.getModules().entrySet()) {
                PlannedChange c = new PlannedChange();
                c.setGroup("模块开关");
                c.setKey(e.getKey());
                c.setDetail(Boolean.TRUE.equals(e.getValue()) ? "开启" : "关闭");
                c.setExists(factorySettingsRepository.findByFactoryId(factoryId).isPresent());
                c.setAction("覆盖");   // 开关一定是就地改, 与落库一致
                out.add(c);
            }
        }
        if (sd.getMetricWatches() != null) {
            for (ApplyAIInitRequest.MetricWatchItem w : sd.getMetricWatches()) {
                InterviewAnomalyMetricCatalog.Entry e =
                        InterviewAnomalyMetricCatalog.find(w.getMetricName());
                if (e == null || Boolean.FALSE.equals(w.getEnabled())) {
                    continue;
                }
                boolean exists = !alertThresholdRepository
                        .findByFactoryIdAndMetricName(factoryId, e.metricName()).isEmpty();
                PlannedChange ch = new PlannedChange();
                ch.setGroup("盯指标");
                ch.setKey(e.label());
                ch.setDetail(e.detail()
                        + (w.getThreshold() != null
                                ? "; 阈值 " + w.getThreshold().toPlainString() + e.unit() : ""));
                ch.setExists(exists);
                ch.setAction(exists ? "跳过" : "新建");
                out.add(ch);
            }
        }
        // ⚠️ 指标停用也要摆给人看 —— 「新增落库类型时, 不在这里登记就等于没给客户看过」。
        //    ⛔ 但措辞与模块那条**不同**: 停用指标只是看板少一格, 不该也写「403」。
        if (sd.getIndicatorOptOut() != null) {
            for (String code : sd.getIndicatorOptOut()) {
                String resolvedCode = com.cretas.aims.service.indicator.FactoryIndicatorProvisioner.optOutCode(code);
                if (resolvedCode == null) {
                    continue;
                }
                var ind = indicatorRepository.findByFactoryIdAndCode(factoryId, resolvedCode);
                PlannedChange ch = new PlannedChange();
                ch.setGroup("指标");
                ch.setKey(ind.map(com.cretas.aims.entity.indicator.Indicator::getName)
                        .orElse(code.trim()));
                ch.setDetail(ind.isPresent()
                        ? "停用(看板上不再显示; 以后可在 Canvas 里打开, 数据不丢)"
                        : "⚠️ 这个指标本厂没有, 会被跳过");
                ch.setExists(ind.isPresent());
                ch.setAction(ind.isPresent() ? "停用" : "跳过");
                out.add(ch);
            }
        }
        if (sd.getModuleConfigs() != null) {
            for (ApplyAIInitRequest.ModuleConfigItem m : sd.getModuleConfigs()) {
                InterviewModuleConfigCatalog.Entry e =
                        InterviewModuleConfigCatalog.find(m.getModuleId());
                if (e == null) {
                    continue;
                }
                boolean exists = featureConfigRepository
                        .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, e.moduleId())
                        .isPresent();
                PlannedChange ch = new PlannedChange();
                ch.setGroup("模块配置");
                ch.setKey(e.label());
                // ⛔ 预览是**人工确认那一关**, 它的措辞必须覆盖这次动作的全部杀伤范围。
                //    2026-08-13 之前这里只写 e.detail() + 「关闭/开启」—— 那只描述了
                //    **前端可见性**(RN 藏屏幕)。而 apply 现在会同时写
                //    factory_module_configs, 于是「关闭」还意味着**后端那些接口开始 403**。
                //    平台管理员看不到这一半就点了确认, 等于在不知情的情况下关掉一批 API。
                //    ⚠️ 这是我自己的改动放大了杀伤范围、而人工确认的描述没跟着变 ——
                //       与本仓 rules 形态 B 同源: 闸(这里是"确认关")的形态变了, 描述没变。
                //    ⛔ 报的是 gateCodes 而不是硬编码的处数: 处数会漂, 码不会。
                boolean turningOff = Boolean.FALSE.equals(m.getEnabled());
                String gateNote;
                if (e.gateCodes().isEmpty()) {
                    gateNote = "; 后端没有对应门禁, 只影响前端可见性";
                } else if (turningOff) {
                    gateNote = "; ⚠️ 后端 " + String.join("/", e.gateCodes())
                            + " 的接口也会一并拦截(403)";
                } else {
                    gateNote = "; 后端门禁: " + String.join("/", e.gateCodes());
                }
                ch.setDetail(e.detail() + (turningOff ? "; 关闭" : "; 开启") + gateNote);
                ch.setExists(exists);
                ch.setAction(exists ? "跳过" : "新建");   // 与落库口径一致: 已有就不动
                out.add(ch);
            }
        }
        if (sd.getAlerts() != null) {
            for (ApplyAIInitRequest.AlertItem a : sd.getAlerts()) {
                InterviewAlertCatalog.Entry entry = InterviewAlertCatalog.find(a.getAlertType());
                if (entry == null || Boolean.FALSE.equals(a.getEnabled())) {
                    continue;
                }
                boolean exists = !alertRuleRepository
                        .findByFactoryId(factoryId).stream().filter(rule -> rule.getAlertType() == entry.type()).toList().isEmpty();
                PlannedChange ch = new PlannedChange();
                ch.setGroup("告警规则");
                ch.setKey(entry.label());
                ch.setDetail(entry.detail()
                        + (entry.needsAmount() && a.getAmountThreshold() != null
                                ? "; 阈值 ¥" + a.getAmountThreshold().toPlainString() : "")
                        + "; 通知 " + String.join("/", entry.notifyRoleCodes()) + "(站内信)");
                ch.setExists(exists);
                ch.setAction(exists ? "跳过" : "新建");
                out.add(ch);
            }
        }
        if (sd.getCodeSegments() != null) {
            for (ApplyAIInitRequest.CodeSegmentItem c : sd.getCodeSegments()) {
                String code = c.getCode() == null ? "" : c.getCode().trim();
                boolean exists = codeSegmentRepository
                        .findByFactoryIdAndLevelOrderBySortOrderAscIdAsc(factoryId, (short) 1).stream().anyMatch(node -> java.util.Objects.equals(node.getSegmentLabel(), c.getLabel() == null ? null : c.getLabel().trim()));
                // ⚠️ 段码在工厂内唯一, 已存在就是跳过(service 会抛 409, 这里提前说清楚)
                PlannedChange ch = new PlannedChange();
                ch.setGroup("编码分段");
                ch.setKey(code);
                ch.setDetail("L1 " + c.getLabel());
                ch.setExists(exists);
                ch.setAction(exists ? "跳过" : "新建");
                out.add(ch);
            }
        }
        if (Boolean.TRUE.equals(sd.getProvisionDefaultUsers())) {
            long existing = userRepository.findByFactoryId(factoryId).size();
            PlannedChange c = new PlannedChange();
            c.setGroup("登录账号");
            c.setKey("默认账号");
            // ⛔ 初始密码是随机的且**不回显** —— 交付方式是管理员用重置接口设一个再告诉客户。
            //    这里把这句话写进预览, 否则客户会以为建完就能登录。
            c.setDetail(existing > 0
                    ? "本厂已有 " + existing + " 个账号, 不会重复建"
                    : "建 3 个: 超管/车间主管/操作员; 初始密码随机且不回显, "
                      + "需管理员用重置接口设定后交付");
            c.setExists(existing > 0);
            c.setAction(existing > 0 ? "跳过" : "新建");
            out.add(c);
        }
        if (sd.getThresholds() != null) {
            for (ApplyAIInitRequest.ThresholdItem t : sd.getThresholds()) {
                String key = t.getKey() == null ? "" : t.getKey().trim();
                boolean exists = thresholdRepository
                        .findByFactoryIdAndThresholdKey(factoryId, key).isPresent();
                PlannedChange c = new PlannedChange();
                c.setGroup("预警阈值");
                c.setKey(key);
                c.setDetail("设为 " + t.getValue());
                c.setExists(exists);
                c.setAction("覆盖");   // upsert, 与落库一致
                out.add(c);
            }
        }
        // 关键控制点(HACCP)—— 2026-08-15 段② 判据一补的那一类。
        //
        // 🔴 缺临界限值的那几条 apply **整条不写**(库里三列非空, 且不许补默认值),
        //    所以预览里就要**当场说清楚**是「写入」还是「跳过·缺限值」——
        //    否则客户在预览里看到它、做完却找不到,那正是「预览在骗人」。
        if (sd.getHaccpCheckpoints() != null) {
            for (ApplyAIInitRequest.HaccpItem h : sd.getHaccpCheckpoints()) {
                // ⛔ 与 apply 第 16 步同一条: haccp 没有 validator 分支,
                //    这个循环也是唯一的防线。而**预览跑在 apply 之前** ——
                //    这里不防的话客户在预览那一步就 500, 连「跳过」都看不到。
                //    (2026-08-15 实发过 apply 侧的 NPE, 顺着同一形状把这里一起补上:
                //     「修好一个口径不等于修好这个口径」。)
                // ⚠️ 这里**静默** continue, 而 apply 那边会 add("(空条目)") 告诉客户 ——
                //    两侧刻意不同, 因为**契约不同**:
                //      预览回答「将要创建哪些东西」—— null 不是一件将要创建的东西,
                //        列成一行「跳过·空条目」只会让客户去找一个并不存在的控制点;
                //      apply 回答「你送来的每一条, 我拿它怎么办了」—— written+skipped
                //        应当能与客户送来的条数对上, 静默丢一条会让这个数对不上。
                //    ⛔ 不写这句, 下一个人看到两侧不一致会去「对齐」其中一侧,
                //      而他不知道该往哪边对(形态 D)。理由同样写在
                //      scripts/tests/apply-field-reachability.txt 的 haccpCheckpoints 条。
                if (h == null) {
                    continue;
                }
                String name = h.getName() == null ? "" : h.getName().trim();
                if (name.isEmpty()) {
                    continue;
                }
                // ⚠️ 这**四**条判断必须与 FactoryBuildApplyService 第 16 步**逐条一致** ——
                //    预览说「会写」而 apply 跳过, 就是「预览在骗人」, 本仓发生过。
                //    ⛔ 白名单引用同一份 AssemblyHaccpContract.HAZARD_TYPES, 不在这里再抄一遍。
                //
                // 🔴 2026-08-16: 这里原来只有**三**条, 缺第四条「已存在」——
                //    而上面那句「必须逐条一致」就写在缺口的正上方。
                //    重复建厂时预览逐条写「新建」, 回执却是「杀菌(已存在)」,
                //    正是这段注释自己警告过的那件事。
                //    ⛔ 幂等键走 HaccpCheckpoint#checkpointCode, 不在这里抄表达式。
                String skip = null;
                if (h.getCriticalLimitMin() == null || h.getCriticalLimitMax() == null
                        || h.getUnit() == null || h.getUnit().trim().isEmpty()) {
                    skip = "⛔ 没给临界限值 —— 不写入(系统不会替你编一个限值)";
                } else if (h.getCriticalLimitMin().compareTo(h.getCriticalLimitMax()) > 0) {
                    skip = "⛔ 限值反了(下限 " + h.getCriticalLimitMin()
                            + " > 上限 " + h.getCriticalLimitMax() + ")—— 不写入";
                } else if (h.getHazardType() == null
                        || !AssemblyHaccpContract.HAZARD_TYPES.contains(h.getHazardType().trim())) {
                    skip = "⛔ 危害类型不合法: " + h.getHazardType() + " —— 不写入";
                } else if (haccpCheckpointRepository
                        .findByFactoryIdAndCheckpointCode(
                                factoryId, AssemblyHaccpContract.checkpointCode(name))
                        .isPresent()) {
                    // ⚠️ 与前三条不同: 这一条不是「载荷有问题」而是「库里已经有了」,
                    //    所以措辞不带 ⛔ —— 它是正常的幂等跳过, 不是客户要去修的东西。
                    skip = "已存在 —— 跳过(不覆盖)";
                }
                PlannedChange c = new PlannedChange();
                c.setGroup("关键控制点");
                c.setKey(name);
                c.setDetail(skip != null ? skip
                        : h.getCriticalLimitMin() + "~" + h.getCriticalLimitMax()
                                + " " + h.getUnit().trim());
                c.setExists(skip != null);
                c.setAction(skip != null ? "跳过" : "新建");
                out.add(c);
            }
        }
        appendDraftIntentChanges(out, request.getDraftCapabilityIntents());
        return out;
    }

    /**
     * Add only an explicit, empty capability lane.  There is intentionally no
     * SuggestedData fabrication here: a draft lane remains pending until its
     * own proposer receives real business facts.
     */
    private void appendDraftIntentChanges(List<PlannedChange> out,
                                          List<FactoryAssemblyDraftIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return;
        }
        for (FactoryAssemblyDraftIntent intent : intents) {
            PlannedChange change = new PlannedChange();
            change.setGroup(intent.groupLabel());
            change.setKey(intent.capabilityId());
            change.setDetail(intent.label() + "；仅记录草稿意向，待补充业务事实后生成，不创建正式记录");
            change.setAction("草稿待填");
            change.setExists(false);
            out.add(change);
        }
    }

    private String conversionProvenance(List<ConversionEvidence> evidence, String fromCode, String toCode) {
        return Optional.ofNullable(evidence).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .filter(item -> Objects.equals(fromCode, item.getFromCode() == null ? "" : item.getFromCode().trim()))
                .filter(item -> Objects.equals(toCode, item.getToCode() == null ? "" : item.getToCode().trim()))
                .map(ConversionEvidence::getProvenance)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private PlannedChange change(String group, String key, String detail,
                                 boolean exists, boolean overwrite) {
        PlannedChange c = new PlannedChange();
        c.setGroup(group);
        c.setKey(key);
        c.setDetail(detail);
        c.setExists(exists);
        c.setAction(!exists ? "新建" : (overwrite ? "覆盖" : "跳过"));
        return c;
    }

    /**
     * 本次载荷里这个 code 对应的<b>中文名</b>;查不到就原样返回 code。
     *
     * <p>⛔ 用途只有一个:把 detail(<b>给人看的那一栏</b>)里的机器码换成客户说过的名字。
     * 键仍然是 code —— 那是给机器对账用的,⛔ 不许改。
     *
     * <p>⚠️ 查不到时**原样返回 code 而不是留空**:留空会让客户看到「 → 冻干鸡胸粒」
     * 这种缺了一半的话,而那比一个看不懂的编号更糟(他会以为系统漏配了一端)。
     */
    private static String nameOfCode(List<ApplyAIInitRequest.TypeItem> items, String code) {
        if (items == null || code == null) {
            return code == null ? "" : code;
        }
        for (ApplyAIInitRequest.TypeItem t : items) {
            if (t != null && code.equals(t.getCode() == null ? null : t.getCode().trim())
                    && !isBlank(t.getName())) {
                return t.getName().trim();
            }
        }
        return code;
    }

    /**
     * 转换率在<b>预览</b>与<b>回执</b>里的键 —— 全仓只有这一处定义。
     *
     * <p>🔴 2026-08-16 实测:预览拼的是 {@code from→to},{@code FactoryBuildApplyService}
     * 拼的是 {@code from->to} —— <b>同一件事在两张单子上用了两种写法</b>,
     * 于是「预览 = 实际写入 = 完成回执,三者对得上」这件事<b>没法机械核对</b>
     * (当时 {@code PreviewMatchesApplyTest} 自己就核不了)。
     *
     * <p>⚠️ 这是本仓的形态 D:同一个东西有两份,它一定会漂 —— 而这一份已经漂了。
     * ⛔ 所以不是「把 {@code ->} 改成 {@code →}」,是<b>收敛成一处</b>:
     * apply 侧调这个方法,写法只能有一种。
     *
     * <p>⚠️ 盲区:两处调用点必须都走它。{@code PreviewMatchesApplyTest} 的
     * {@code parityConversionsUseTheSameKeyShape} 钉住这一点(它比的是两侧的键集合相等)。
     */
    static String conversionKey(String fromCode, String toCode) {
        return (fromCode == null ? "" : fromCode.trim())
                + "→"
                + (toCode == null ? "" : toCode.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
