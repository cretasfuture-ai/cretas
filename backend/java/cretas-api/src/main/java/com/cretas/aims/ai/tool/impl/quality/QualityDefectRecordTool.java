package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.WritePreviewSupport;
import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.DefectStatus;
import com.cretas.aims.entity.enums.DefectType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.QualityDefectService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.RawMaterialTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sprint4-H Q-PROCESS-1: 登记质检不良 AI Tool.
 *
 * <p>当 LLM 识别用户意图为"登记质检不良 / 记录不良"时调用. 必填:
 * qualityInspectionId, defectType, quantity. 可选: materialId, cause.
 */
@Slf4j
@Component
public class QualityDefectRecordTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityDefectService qualityDefectService;

    /** 只给预览用 (只读): 确认质检记录存在且属于本工厂, 与 recordDefect 的校验同一道。 */
    @Autowired(required = false)
    @Lazy
    private QualityInspectionService qualityInspectionService;

    /** 只给预览用 (只读): 把质检记录上的批次编号换成「批次号（产品名）」。 */
    @Autowired(required = false)
    @Lazy
    private ProductionBatchRepository productionBatchRepository;

    /** 只给预览用 (只读): 把关联物料 id 换成名字。 */
    @Autowired(required = false)
    @Lazy
    private RawMaterialTypeService rawMaterialTypeService;

    @Override
    public String getToolName() {
        return "quality_defect_record";
    }

    @Override
    public String getDescription() {
        return "登记工序质检不良记录。需要指定关联的质检记录ID、缺陷类型、数量，可选指定物料和原因。" +
                "适用场景：质检员发现不合格产品后, 逐条登记不良详情用于后续闭环处理。" +
                "不良类型: APPEARANCE(外观)/WEIGHT(重量)/COMPOSITION(成分)/MICROBIAL(微生物)/" +
                "PACKAGING(包装)/FOREIGN_OBJECT(异物)/SENSORY(感官)/SHELF_LIFE(保质期)/OTHER。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("qualityInspectionId", Map.of(
                "type", "string",
                "description", "关联质检记录编号"));
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
        schema.put("required", Arrays.asList("qualityInspectionId", "defectType", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("qualityInspectionId", "defectType", "quantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String inspectionId = getString(params, "qualityInspectionId");
        String defectTypeStr = getString(params, "defectType");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String materialId = getString(params, "materialId");
        String cause = getString(params, "cause");

        DefectType defectType;
        try {
            defectType = DefectType.valueOf(defectTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 可选值给中文名 (枚举 getDisplayName), 不贴枚举码 —— 一线读不懂 FOREIGN_OBJECT。
            String options = java.util.Arrays.stream(DefectType.values())
                    .map(DefectType::getDisplayName)
                    .collect(java.util.stream.Collectors.joining(" / "));
            throw new IllegalArgumentException("无效的缺陷类型: " + defectTypeStr + "。可选: " + options);
        }

        Long userId = extractUserIdFromContext(context);

        QualityDefect saved = qualityDefectService.recordDefect(
                factoryId, inspectionId, materialId, defectType, quantity, cause, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("defectId", saved.getId());
        result.put("qualityInspectionId", saved.getQualityInspectionId());
        result.put("defectType", saved.getDefectType().name());
        result.put("defectTypeDisplay", saved.getDefectType().getDisplayName());
        result.put("quantity", saved.getQuantity());
        result.put("status", saved.getStatus().name());
        result.put("message", String.format("已登记不良: %s (%s) 数量 %s, 待分派处理",
                saved.getDefectType().getDisplayName(), saved.getId(), saved.getQuantity()));
        return result;
    }

    /**
     * 只读预览 —— AI 对话里「预览 → 确认」的预览段。⛔ 只读质检记录, 不调 {@code recordDefect}。
     *
     * <p>镜像 {@code QualityDefectServiceImpl#recordDefect} 的拒绝条件: 缺陷类型必须是九种代码之一
     * (与 {@link #doExecute} 同一个 {@code DefectType.valueOf}, 只认代码、不认中文名 —— 认了中文名
     * 而 execute 不认, 确认之后就会失败)、数量 &gt; 0、质检记录存在且属于本工厂。
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

        DefectType defectType;
        try {
            defectType = DefectType.valueOf(defectTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID", String.format(
                    "不认识的不良类型「%s」。可选：%s。", defectTypeStr, defectTypeOptions()), null);
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID",
                    "不良数量必须大于 0。请告诉我发现了多少个不良。", null);
        }

        String inspectionTitle = "这条质检记录";
        String inspectionResult = WritePreviewSupport.EMPTY;
        if (qualityInspectionService != null) {
            QualityInspection inspection;
            try {
                inspection = qualityInspectionService.getInspectionById(factoryId, inspectionId);
            } catch (EntityNotFoundException | ResourceNotFoundException notVisible) {
                return WritePreviewSupport.blocked("INSPECTION_NOT_FOUND",
                        "找不到这条质检记录，或它不属于本工厂。请核对后再试。", null);
            }
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
        return WritePreviewSupport.ready(message, current, next, null);
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
            case "qualityInspectionId" -> "请问是哪一条质检记录？";
            case "defectType" -> "请问缺陷类型是？(外观/重量/成分/微生物/包装/异物/感官/保质期/其他)";
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
