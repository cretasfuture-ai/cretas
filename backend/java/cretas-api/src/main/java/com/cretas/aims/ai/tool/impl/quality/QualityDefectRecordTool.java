package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.WritePreviewSupport;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.DefectStatus;
import com.cretas.aims.entity.enums.DefectType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.QualityDefectService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.RawMaterialTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sprint4-H Q-PROCESS-1: 登记质检不良 AI Tool.
 *
 * <p>当 LLM 识别用户意图为"登记质检不良 / 记录不良"时调用. 必填:
 * qualityInspectionId, defectType, quantity. 可选: materialId, cause.
 *
 * <h3>质检记录怎么指 (2026-10-07)</h3>
 * 质检员不认得质检记录的内部编号 (UUID), 只认得批次号。{@code qualityInspectionId} 因此接受两种写法,
 * 预览与执行共用 {@link #resolveInspection} 解析 (照 #946 MaterialBatchUseTool 的「先主键、再批次号」):
 * <ol>
 *   <li>先按主键查 (老调用方 / 前端按钮传的是主键), 且必须属于本工厂;</li>
 *   <li>查不到再按「生产批次号」找该批次的质检记录 (多条时取最近一条, 确认卡里写明日期与结果, 由本人核对)。</li>
 * </ol>
 * 都不命中 (或根本没说) 时, 预览不报「缺少编号」, 而是列出他最近的几条质检记录 (批次号 + 产品 + 日期 + 结果)
 * 让他选, 见 {@link #askWhichInspection}。⛔ 不用「第几条」这类序号: 序号是相对一份会变的清单的位置,
 * 确认令牌带的是原始说法, 清单在预览与确认之间变了就会登记到另一条记录上。
 */
@Slf4j
@Component
public class QualityDefectRecordTool extends AbstractBusinessTool {

    /** 用户常说法统一映射到枚举；追问、预览和执行都只通过这一处解析。 */
    private static final Map<String, DefectType> DEFECT_TYPE_ALIASES = buildDefectTypeAliases();

    /** 追问里最多列几条最近的质检记录。 */
    static final int MAX_CANDIDATES = 5;

    /** 质检员只能在自己的质检记录里选 (其它角色沿用本工厂范围)。 */
    private static final String SELF_SCOPED_ROLE = "quality_inspector";

    static final String INSPECTION_NOT_FOUND_MESSAGE = "找不到这条质检记录，或它不属于本工厂。请核对后再试。";

    private static final Sort RECENT_FIRST = Sort.by(
            Sort.Order.desc("inspectionDate"), Sort.Order.desc("createdAt"));

    @Autowired
    @Lazy
    private QualityDefectService qualityDefectService;

    /** 预览和执行共用的只读解析: 确认质检记录存在且属于本工厂。 */
    @Autowired(required = false)
    @Lazy
    private QualityInspectionService qualityInspectionService;

    /** 预览和执行展示使用的只读批次号解析。 */
    @Autowired(required = false)
    @Lazy
    private ProductionBatchRepository productionBatchRepository;

    /** 只给预览用 (只读): 把关联物料 id 换成名字。 */
    @Autowired(required = false)
    @Lazy
    private RawMaterialTypeService rawMaterialTypeService;

    /**
     * 「批次号 → 质检记录」与「最近的质检记录」候选的只读查询。每条查询都带 factoryId (本类再认一次, 双保险)。
     * ⛔ 不设 required=false: 缺了它整条批次号解析 / 候选清单静默失效, 一线又回到「请提供编号」(#946 同款教训)。
     */
    @Autowired
    @Lazy
    private QualityInspectionRepository qualityInspectionRepository;

    @Override
    public String getToolName() {
        return "quality_defect_record";
    }

    @Override
    public String getDescription() {
        return "登记工序质检不良记录。需要指定关联的质检记录 (说质检所在的批次号即可)、缺陷类型、数量，可选指定物料和原因。" +
                "适用场景：质检员发现不合格产品后, 逐条登记不良详情用于后续闭环处理。" +
                "不良类型: APPEARANCE(外观)/WEIGHT(重量)/COMPOSITION(成分)/MICROBIAL(微生物)/" +
                "PACKAGING(包装)/FOREIGN_OBJECT(异物)/SENSORY(感官)/SHELF_LIFE(保质期)/OTHER。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        // 质检员不认得质检记录的内部编号, 只认得批次号: 写批次号 (如 B20261006-01) 即可, 工具内部解析成真实记录。
        // 用户没说就不要填 —— 不要编造, 缺了由预览列出他最近的质检记录让他选。
        properties.put("qualityInspectionId", Map.of(
                "type", "string",
                "description", "质检记录所在的生产批次号，例如 B20261006-01。用户没说就不要填，不要编造"));
        properties.put("defectType", Map.of(
                "type", "string",
                "description", "缺陷类型",
                "enum", Arrays.asList(
                        "APPEARANCE", "WEIGHT", "COMPOSITION", "MICROBIAL",
                        "PACKAGING", "FOREIGN_OBJECT", "SENSORY", "SHELF_LIFE", "OTHER")));
        properties.put("quantity", Map.of(
                "type", "number",
                "description", "不良数量 (必须 > 0)"));
        properties.put("materialId", Map.of(
                "type", "string",
                "description", "关联物料 ID (可选, raw_material_types.id)"));
        properties.put("cause", Map.of(
                "type", "string",
                "description", "缺陷原因描述 (可选, 最长 500 字)",
                "maxLength", 500));

        schema.put("properties", properties);
        // 对话里的缺参追问 (Slot Filling) 读的是这份 required, 它只会问「请提供<字段说明>」。
        // 质检记录不在这里: 缺了由预览带「最近的质检记录」候选来问 (见 askWhichInspection),
        // 而不是让质检员去报一个他不可能知道的编号。执行期的必填校验仍是 getRequiredParameters() 三项。
        schema.put("required", Arrays.asList("defectType", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("qualityInspectionId", "defectType", "quantity");
    }

    /** 预览阶段缺质检记录不当「缺参」报: 交给 doPreview 带候选清单去问。其余缺参照旧。 */
    @Override
    protected List<String> previewMissingParameters(Map<String, Object> params) {
        List<String> missing = new ArrayList<>(validateRequiredParams(params));
        missing.remove("qualityInspectionId");
        return missing;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String inspectionRef = getString(params, "qualityInspectionId");
        String defectTypeStr = getString(params, "defectType");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String materialId = getString(params, "materialId");
        String cause = getString(params, "cause");

        DefectType defectType = parseDefectType(defectTypeStr);
        if (defectType == null) {
            // 可选值给中文名 (枚举 getDisplayName), 不贴枚举码 —— 一线读不懂 FOREIGN_OBJECT。
            String options = java.util.Arrays.stream(DefectType.values())
                    .map(DefectType::getDisplayName)
                    .collect(java.util.stream.Collectors.joining(" / "));
            throw new IllegalArgumentException("无效的缺陷类型: " + defectTypeStr + "。可选: " + options);
        }

        Long userId = extractUserIdFromContext(context);

        // 用户给的是批次号 (或老调用方给的主键): 写路径必须拿真实主键, 与预览同一个 resolveInspection。
        if (qualityInspectionService == null) {
            throw new BusinessException("不良尚未登记：无法读取质检记录。请稍后重试，确认成功后再继续。");
        }
        String inspectionId = inspectionRef;
        Resolution resolved = resolveInspection(factoryId, inspectionRef);
        if (resolved.inspection() == null) {
            throw new ResourceNotFoundException(INSPECTION_NOT_FOUND_MESSAGE);
        }
        inspectionId = resolved.inspection().getId();
        String inspectionBatchNumber = batchNumber(factoryId, resolved.inspection().getProductionBatchId());

        QualityDefect saved = qualityDefectService.recordDefect(
                factoryId, inspectionId, materialId, defectType, quantity, cause, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("defectId", saved.getId());
        result.put("qualityInspectionId", saved.getQualityInspectionId());
        result.put("defectType", saved.getDefectType().name());
        result.put("defectTypeDisplay", saved.getDefectType().getDisplayName());
        result.put("quantity", saved.getQuantity());
        result.put("status", saved.getStatus().name());
        result.put("message", String.format("已登记不良：%s %s 个，登记在 %s 的质检上，状态：%s。",
                saved.getDefectType().getDisplayName(), WritePreviewSupport.quantityText(saved.getQuantity(), null),
                inspectionBatchNumber, DefectStatus.OPEN.getDisplayName()));
        return result;
    }

    /**
     * 只读预览 —— AI 对话里「预览 → 确认」的预览段。⛔ 只读质检记录, 不调 {@code recordDefect}。
     *
     * <p>镜像 {@code QualityDefectServiceImpl#recordDefect} 的拒绝条件: 缺陷类型必须能由统一词表
     * 解析为九种类型之一 (与 {@link #doExecute} 共用同一个解析方法)、数量 &gt; 0、质检记录存在且属于本工厂。
     */
    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String inspectionId = getString(params, "qualityInspectionId");
        String defectTypeStr = getString(params, "defectType");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String materialId = getString(params, "materialId");
        String cause = getString(params, "cause");

        DefectType defectType = parseDefectType(defectTypeStr);
        if (defectType == null) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID", String.format(
                    "不认识的不良类型「%s」。可选：%s。", defectTypeStr, defectTypeOptions()), null);
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID",
                    "不良数量必须大于 0。请告诉我发现了多少个不良。", null);
        }

        String inspectionTitle = "这条质检记录";
        String inspectionResult = WritePreviewSupport.EMPTY;
        int sameBatchCount = 1;
        if (qualityInspectionService != null) {
            Resolution resolved = resolveInspection(factoryId, inspectionId);
            if (resolved.inspection() == null) {
                // 没说 / 说的认不出: 不报「缺少编号」, 列出他最近的质检记录让他选 (只读)
                return askWhichInspection(factoryId, inspectionId, getUserId(context), getUserRole(context));
            }
            QualityInspection inspection = resolved.inspection();
            sameBatchCount = resolved.sameBatchCount();
            inspectionTitle = QualityPreviewSupport.inspectionTitle(inspection,
                    QualityPreviewSupport.batchTitle(productionBatchRepository, factoryId,
                            inspection.getProductionBatchId()));
            inspectionResult = QualityPreviewSupport.resultLabel(inspection.getResult());
        }

        String typeName = defectType.getDisplayName();
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("质检记录", inspectionTitle);
        current.put("质检结果", inspectionResult);
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("质检记录", inspectionTitle);
        next.put("质检结果", inspectionResult);   // 登记不良不改质检结果, 两侧相同
        next.put("新增不良", typeName + " × " + WritePreviewSupport.quantityText(quantity, null));
        next.put("处理状态", DefectStatus.OPEN.getDisplayName());
        if (WritePreviewSupport.notBlank(cause)) {
            next.put("不良原因", cause);
        }
        if (WritePreviewSupport.notBlank(materialId)) {
            next.put("关联物料", materialName(factoryId, materialId));
        }
        String message = String.format("确认在 %s 登记不良：%s %s？登记后状态是「%s」，等待分派处理。",
                inspectionTitle, typeName, WritePreviewSupport.quantityText(quantity, null),
                DefectStatus.OPEN.getDisplayName());
        if (sameBatchCount > 1) {
            // 一个批次有多条质检记录时按批次号只能取最近一条: 当面说清楚, 不对的话取消后换个说法
            message += String.format("（这个批次有 %d 条质检记录，这是日期最近的一条；不是它就取消，改说别的批次号。）",
                    sameBatchCount);
        }
        return WritePreviewSupport.ready(message, current, next, null);
    }

    // ==================== 质检记录解析 (预览 / 执行共用) ====================

    /** 解析结果: {@code inspection} 为 null = 没解析出来; {@code sameBatchCount} = 按批次号找到的同批次记录数 (主键命中为 1)。 */
    private record Resolution(QualityInspection inspection, int sameBatchCount) {
        static Resolution none() {
            return new Resolution(null, 0);
        }
    }

    /**
     * 把用户给的「质检记录」解析成真实记录。<b>预览与执行必须共用这一个方法</b>, 否则预览能找到、执行找不到 (或反过来)。
     *
     * <ol>
     *   <li>先按主键查 (老行为), 且必须属于本工厂;</li>
     *   <li>查不到再按 factoryId + 生产批次号找该批次的质检记录 (一线只认得批次号), 同样必须属于本工厂;
     *       有多条取日期最近的一条 (并带出条数, 预览里当面说清楚)。</li>
     * </ol>
     * 只读。⛔ 不在这里抛异常: 别厂的记录与不存在同一个出口, 不泄露存在性。
     */
    private Resolution resolveInspection(String factoryId, String ref) {
        if (!WritePreviewSupport.notBlank(ref)) {
            return Resolution.none();
        }
        String key = ref.trim();
        QualityInspection byId = readOwnInspection(factoryId, key);
        if (byId != null) {
            return new Resolution(byId, 1);
        }
        if (productionBatchRepository != null && qualityInspectionRepository != null) {
            Optional<ProductionBatch> batch = productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, key);
            if (batch != null && batch.isPresent() && factoryId.equals(batch.get().getFactoryId())
                    && batch.get().getId() != null) {
                List<QualityInspection> own = ownOnly(factoryId, null, qualityInspectionRepository
                        .findByFactoryIdAndProductionBatchId(factoryId, batch.get().getId(),
                                PageRequest.of(0, MAX_CANDIDATES, RECENT_FIRST)));
                if (!own.isEmpty()) {
                    return new Resolution(own.get(0), own.size());
                }
            }
        }
        return Resolution.none();
    }

    /** 按主键读质检记录, 且必须属于本工厂 (service 抛 404 / 不属于本厂都当没有; 工具这一层再认一次 factoryId)。 */
    private QualityInspection readOwnInspection(String factoryId, String id) {
        QualityInspection inspection;
        try {
            inspection = qualityInspectionService.getInspectionById(factoryId, id);
        } catch (EntityNotFoundException | ResourceNotFoundException notVisible) {
            return null;
        }
        return inspection != null && factoryId.equals(inspection.getFactoryId()) ? inspection : null;
    }

    /** 分页结果里只留本工厂 (质检员视角时还须是他本人做的) 的记录。 */
    private static List<QualityInspection> ownOnly(String factoryId, Long onlyInspectorId, Page<QualityInspection> page) {
        List<QualityInspection> out = new ArrayList<>();
        if (page == null || page.getContent() == null) {
            return out;
        }
        for (QualityInspection q : page.getContent()) {
            if (out.size() >= MAX_CANDIDATES) {
                break;   // 仓库按 Pageable 只会回这么多; 这里再兜一次, 追问永远不会列出五条以上
            }
            if (q != null && factoryId.equals(q.getFactoryId()) && WritePreviewSupport.notBlank(q.getId())
                    && (onlyInspectorId == null || onlyInspectorId.equals(q.getInspectorId()))) {
                out.add(q);
            }
        }
        return out;
    }

    /**
     * 最近的质检记录 (最多 {@link #MAX_CANDIDATES} 条, 日期近的在前)。只读; 读法 = 本工厂范围的分页读取
     * (与 {@code QualityInspectionService#getInspections} 同一个仓库入口), 质检员只看自己做的 ——
     * 取不到他是谁时不退回「全厂」, 直接没有候选。
     */
    private List<QualityInspection> recentInspections(String factoryId, Long userId, String role) {
        if (qualityInspectionRepository == null) {
            return List.of();
        }
        Pageable recent = PageRequest.of(0, MAX_CANDIDATES, RECENT_FIRST);
        if (SELF_SCOPED_ROLE.equals(role)) {
            if (userId == null) {
                return List.of();
            }
            return ownOnly(factoryId, userId,
                    qualityInspectionRepository.findByFactoryIdAndInspectorId(factoryId, userId, recent));
        }
        return ownOnly(factoryId, null, qualityInspectionRepository.findByFactoryId(factoryId, recent));
    }

    /**
     * 没说 / 认不出质检记录: 列出最近的几条让他选。回的是 NEED_MORE_INFO (与基类缺参出口同形状, 不铸确认令牌);
     * App 把它显示成「还需要以下信息 … 请补充后重新说一遍」, 所以这里只教他「在话里带上批次号」。
     */
    private Map<String, Object> askWhichInspection(String factoryId, String ref, Long userId, String role) {
        boolean refGiven = WritePreviewSupport.notBlank(ref);
        List<QualityInspection> recent = recentInspections(factoryId, userId, role);
        if (recent.isEmpty()) {
            return refGiven
                    ? WritePreviewSupport.blocked("INSPECTION_NOT_FOUND", INSPECTION_NOT_FOUND_MESSAGE, null)
                    : WritePreviewSupport.blocked("NO_INSPECTION",
                            "现在没有可以登记不良的质检记录。请先完成一次质检，再来登记不良。", null);
        }
        List<String> labels = new ArrayList<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (QualityInspection q : recent) {
            String batch = QualityPreviewSupport.batchTitle(productionBatchRepository, factoryId, q.getProductionBatchId());
            String date = q.getInspectionDate() != null ? q.getInspectionDate().toString() : WritePreviewSupport.EMPTY;
            String result = QualityPreviewSupport.resultLabel(q.getResult());
            labels.add(batch + "，" + date + "，" + result);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("生产批次", batch);
            c.put("质检日期", date);
            c.put("质检结果", result);
            candidates.add(c);
        }
        String who = SELF_SCOPED_ROLE.equals(role) ? "你最近的质检有：" : "最近的质检有：";
        String question = (refGiven ? "没有找到你说的那条质检记录。" : "")
                + "要登记在哪一条质检记录上？" + who + String.join("；", labels)
                + "。直接说批次号就行，例如「" + firstBatchNumber(recent, factoryId) + "」。";
        return new InspectionChoiceQuestion(question, candidates);
    }

    /** 示例里用的批次号: 取候选里第一条能读到批次号的, 读不到就用通用写法。 */
    private String firstBatchNumber(List<QualityInspection> recent, String factoryId) {
        if (productionBatchRepository != null) {
            for (QualityInspection q : recent) {
                if (q.getProductionBatchId() == null) {
                    continue;
                }
                Optional<ProductionBatch> b = productionBatchRepository.findByIdAndFactoryId(q.getProductionBatchId(), factoryId);
                if (b != null && b.isPresent() && WritePreviewSupport.notBlank(b.get().getBatchNumber())) {
                    return b.get().getBatchNumber();
                }
            }
        }
        return "批次号";
    }

    /**
     * 预览里「要用户再补一句」的出口 —— 根级 {@code success=false, status=NEED_MORE_INFO}, 与基类缺参出口同形状
     * ({@code ToolDispatchService.blockedPreviewResponse} 据此回 NEED_MORE_INFO、不铸确认 token)。
     * 专用 Map 子类: 只有本工具自己构造得出来, {@link #buildSuccessResult} 才会改写它 (与 #946 同一做法)。
     */
    private static final class InspectionChoiceQuestion extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        final String question;
        final transient List<Map<String, Object>> candidates;

        InspectionChoiceQuestion(String question, List<Map<String, Object>> candidates) {
            this.question = question;
            this.candidates = candidates;
            put("status", "NEED_MORE_INFO");
        }
    }

    @Override
    protected String buildSuccessResult(Object data) {
        if (data instanceof InspectionChoiceQuestion q) {
            try {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", false);
                result.put("status", "NEED_MORE_INFO");
                result.put("missingParameters", List.of("qualityInspectionId"));
                result.put("needMoreInfo", true);
                result.put("clarificationQuestions", List.of(q.question));
                result.put("message", q.question);
                result.put("error", q.question);
                result.put("candidates", q.candidates);
                return objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                log.error("❌ 构建质检记录追问结果失败", e);
                return buildErrorResult("响应处理失败");
            }
        }
        return super.buildSuccessResult(data);
    }

    private String materialName(String factoryId, String materialId) {
        if (rawMaterialTypeService != null) {
            try {
                return rawMaterialTypeService.getMaterialTypeById(factoryId, materialId).getName();
            } catch (RuntimeException notResolvable) {
                // 查不到名字不拦 (写路径不校验物料), 但也不回显 id
            }
        }
        return "已指定的物料";
    }

    private static String defectTypeOptions() {
        return java.util.Arrays.stream(DefectType.values())
                .map(DefectType::getDisplayName)
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private static Map<String, DefectType> buildDefectTypeAliases() {
        Map<String, DefectType> aliases = new LinkedHashMap<>();
        alias(aliases, DefectType.APPEARANCE, "外观", "外观不良", "外观问题", "外观缺陷");
        alias(aliases, DefectType.WEIGHT, "重量", "重量不良", "重量问题", "重量不达标", "分量不够", "缺斤少两");
        alias(aliases, DefectType.COMPOSITION, "成分", "成分不良", "成分异常", "成分问题");
        alias(aliases, DefectType.MICROBIAL, "微生物", "微生物不良", "微生物超标", "微生物问题");
        alias(aliases, DefectType.PACKAGING, "包装", "包装不良", "包装缺陷", "包装问题");
        alias(aliases, DefectType.FOREIGN_OBJECT, "异物", "异物不良", "异物问题");
        alias(aliases, DefectType.SENSORY, "感官", "感官不良", "感官异常", "感官问题");
        alias(aliases, DefectType.SHELF_LIFE, "保质期", "保质期不良", "保质期问题", "保质期异常");
        alias(aliases, DefectType.OTHER, "其他", "其它", "其他问题", "其它问题");
        for (DefectType type : DefectType.values()) {
            aliases.put(type.name(), type);
        }
        return Collections.unmodifiableMap(aliases);
    }

    private static void alias(Map<String, DefectType> aliases, DefectType type, String... values) {
        for (String value : values) {
            aliases.put(value, type);
        }
    }

    private static DefectType parseDefectType(String value) {
        if (!WritePreviewSupport.notBlank(value)) {
            return null;
        }
        DefectType type = DEFECT_TYPE_ALIASES.get(value.trim());
        if (type != null) {
            return type;
        }
        return DEFECT_TYPE_ALIASES.get(value.trim().toUpperCase(Locale.ROOT));
    }

    private String batchNumber(String factoryId, Long productionBatchId) {
        if (productionBatchRepository == null || productionBatchId == null) {
            throw new BusinessException("不良尚未登记：无法读取生产批次号。请稍后重试，确认成功后再继续。");
        }
        return productionBatchRepository.findByIdAndFactoryId(productionBatchId, factoryId)
                .map(ProductionBatch::getBatchNumber)
                .filter(WritePreviewSupport::notBlank)
                .orElseThrow(() -> new BusinessException(
                        "不良尚未登记：无法读取生产批次号。请稍后重试，确认成功后再继续."));
    }

    private Long extractUserIdFromContext(Map<String, Object> context) {
        Object userIdObj = context.get("userId");
        if (userIdObj == null) return null;
        if (userIdObj instanceof Long l) return l;
        try {
            return Long.parseLong(userIdObj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "qualityInspectionId" -> "请问登记在哪一条质检记录上？说批次号就行";
            case "defectType" -> "请问缺陷类型是？(" + defectTypeOptions() + ")";
            case "quantity" -> "请问不良数量是多少？";
            case "materialId" -> "请问关联的是哪种物料？说名称就行（可选）";
            case "cause" -> "请描述缺陷原因 (可选)";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole)
                || "factory_super_admin".equals(userRole)
                || "platform_admin".equals(userRole)
                || "factory_admin".equals(userRole)
                || "quality_inspector".equals(userRole)
                || "quality_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
