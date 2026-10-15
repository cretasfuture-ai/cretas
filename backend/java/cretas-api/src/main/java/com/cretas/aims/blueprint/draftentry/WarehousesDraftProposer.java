package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.WarehouseItem;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftCommand;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.ColdControlType;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftOperation;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftRequest;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.RequestedColdControl;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.RequestedWarehouse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code warehouses} proposal: the storage areas the interview registered
 * ({@code suggestedData.warehouses}), one request for all of them.
 *
 * <p>Pre-filled from facts: code, name, type, remarks. From the command's own rules: {@code active}
 * is always true (the command refuses planning a deactivation), and {@code operation} is CREATE when
 * the factory has no warehouse with that code (the command refuses CREATE on an existing code). When
 * the code already exists the operation is asked, not set to UPDATE: the interview registered a
 * storage area, it did not ask to change the existing one (the old apply path skipped existing
 * codes), so "modify it or drop it from the draft" is the user's call.</p>
 *
 * <p>Cold-chain controls are asked as a whole: the interview never asked about temperatures, so
 * neither "no cold chain" (an empty list) nor any control point is a fact.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class WarehousesDraftProposer implements CapabilityDraftProposer {

    /** Same pattern the command enforces ({@code WAREHOUSE_DRAFT_CODE_INVALID}). */
    private static final String CODE_PATTERN = "[A-Z0-9][A-Z0-9_-]{1,31}";
    private static final Map<WarehouseType, String> TYPE_LABELS = Map.ofEntries(
            Map.entry(WarehouseType.RAW, "原料仓"), Map.entry(WarehouseType.WIP, "在制品仓"),
            Map.entry(WarehouseType.FINISHED, "成品仓"), Map.entry(WarehouseType.LINESIDE, "线边仓"),
            Map.entry(WarehouseType.RETURNS, "退货仓"), Map.entry(WarehouseType.SCRAP, "废料仓"),
            Map.entry(WarehouseType.TEMP, "暂存仓"), Map.entry(WarehouseType.QC, "质检仓"),
            Map.entry(WarehouseType.OUTSOURCE, "委外仓"), Map.entry(WarehouseType.TRANSFER, "调拨在途仓"),
            Map.entry(WarehouseType.SALTED, "盐化仓"), Map.entry(WarehouseType.RD, "研发/中试库"));
    /** The command's lengths ({@code WAREHOUSE_DRAFT_CODE_INVALID}, name, remarks). */
    static final int MAX_CODE = 32;
    static final int MAX_NAME = 100;
    static final int MAX_REMARKS = 1000;
    private static final String CODE_CONSTRAINT = "2–" + MAX_CODE + " 位大写字母、数字、下划线或连字符，草稿内不重复";
    /**
     * Every type the command accepts except the ones the entity marks {@code @Deprecated} (kept only
     * for old rows; a new warehouse is entered with a stage type).
     */
    static final List<Option> TYPE_OPTIONS = Arrays.stream(WarehouseType.values())
            .filter(type -> !deprecated(type))
            .map(type -> new Option(type.name(), TYPE_LABELS.getOrDefault(type, type.name())))
            .toList();
    private static final String TYPE_CONSTRAINT = String.join("、",
            TYPE_OPTIONS.stream().map(Option::value).toList()) + " 之一";
    private static final Option CREATE = new Option(DraftOperation.CREATE.name(), "新建");
    private static final Option UPDATE = new Option(DraftOperation.UPDATE.name(), "修改已有");
    static final List<Option> OPERATION_OPTIONS = List.of(CREATE, UPDATE);
    private static final String OPERATION_CONSTRAINT = "CREATE（新编号）或 UPDATE（库里已有的编号）";
    static final List<Option> COLD_TYPE_OPTIONS = Option.ofEnum(ColdControlType.class, Map.of(
            ColdControlType.FREEZER, "冷冻柜", ColdControlType.REFRIGERATOR, "冷藏柜",
            ColdControlType.COLD_CHAIN_VEHICLE, "冷藏车", ColdControlType.COLD_DISPLAY, "冷藏展示柜",
            ColdControlType.COLD_STORAGE, "冷库"));
    /** The command's lengths and ranges for a cold-chain control ({@code COLD_CONTROL_DRAFT_*}). */
    static final int MAX_EQUIPMENT_CODE = 50;
    static final int MAX_EQUIPMENT_NAME = 200;
    static final int MAX_LOCATION = 200;
    static final int MAX_REASON = 500;
    static final BigDecimal MIN_CELSIUS = new BigDecimal("-273.15");
    static final BigDecimal MAX_CELSIUS = new BigDecimal("100");
    static final int MAX_TOLERANCE_MINUTES = 1440;
    private static final String WHY_NO_WAREHOUSES = "建厂访谈没有登记存放区";
    private static final String WHY_NO_COLD = "建厂访谈没有问温度和冷链控制";
    /** One row of {@code warehouses}: every component of {@link RequestedWarehouse}. */
    static final List<RequiredInput> WAREHOUSE_FIELDS = List.of(
            RequiredInput.oneOf("operation", "新建还是修改已有仓库", WHY_NO_WAREHOUSES, OPERATION_CONSTRAINT, OPERATION_OPTIONS),
            RequiredInput.text("code", "仓库编号", WHY_NO_WAREHOUSES, CODE_CONSTRAINT, MAX_CODE),
            RequiredInput.text("name", "仓库名称", WHY_NO_WAREHOUSES, "1–" + MAX_NAME + " 字，现场人员认得的名称", MAX_NAME),
            RequiredInput.oneOf("type", "仓库类型", WHY_NO_WAREHOUSES, TYPE_CONSTRAINT, TYPE_OPTIONS),
            // The command refuses planning a deactivation (WAREHOUSE_DRAFT_DEACTIVATION_REQUIRES_INVENTORY_REVIEW).
            RequiredInput.bool("active", "是否启用", WHY_NO_WAREHOUSES, "只能选 是：建厂草稿不能规划停用仓库"),
            RequiredInput.text("remarks", "备注", WHY_NO_WAREHOUSES, "不超过 " + MAX_REMARKS + " 字，可以留空", MAX_REMARKS)
                    .allowNull(),
            RequiredInput.text("changeReason", "登记这个仓库的依据", WHY_NO_WAREHOUSES, "1–" + MAX_REASON + " 字", MAX_REASON));
    /** One row of {@code coldControls}: every component of {@link RequestedColdControl}. */
    static final List<RequiredInput> COLD_CONTROL_FIELDS = List.of(
            RequiredInput.oneOf("operation", "新建还是修改已有控制点", WHY_NO_COLD, OPERATION_CONSTRAINT, OPERATION_OPTIONS),
            RequiredInput.text("equipmentCode", "设备编码", WHY_NO_COLD,
                    "2–" + MAX_EQUIPMENT_CODE + " 位大写字母、数字、下划线或连字符，草稿内不重复", MAX_EQUIPMENT_CODE),
            RequiredInput.text("equipmentName", "控制点名称", WHY_NO_COLD, "1–" + MAX_EQUIPMENT_NAME + " 字", MAX_EQUIPMENT_NAME),
            RequiredInput.oneOf("type", "控制点类型", WHY_NO_COLD,
                    String.join("、", COLD_TYPE_OPTIONS.stream().map(Option::value).toList()) + " 之一",
                    COLD_TYPE_OPTIONS),
            RequiredInput.text("warehouseCode", "所在仓库编号", WHY_NO_COLD,
                    "本草稿里的仓库或库里已启用的仓库编号（最长 " + MAX_CODE + " 位）；只有冷藏车可以留空", MAX_CODE).allowNull(),
            RequiredInput.decimal("targetTempMinCelsius", "温度下限（℃）", WHY_NO_COLD,
                    MIN_CELSIUS.toPlainString() + "–" + MAX_CELSIUS.toPlainString() + "℃，不大于温度上限",
                    MIN_CELSIUS, MAX_CELSIUS),
            RequiredInput.decimal("targetTempMaxCelsius", "温度上限（℃）", WHY_NO_COLD,
                    MIN_CELSIUS.toPlainString() + "–" + MAX_CELSIUS.toPlainString() + "℃，不小于温度下限",
                    MIN_CELSIUS, MAX_CELSIUS),
            RequiredInput.integer("toleranceMinutes", "持续偏离多久算异常（分钟）", WHY_NO_COLD,
                    "1–" + MAX_TOLERANCE_MINUTES + " 分钟", 1, MAX_TOLERANCE_MINUTES),
            RequiredInput.text("location", "位置说明", WHY_NO_COLD, "不超过 " + MAX_LOCATION + " 字，可以留空", MAX_LOCATION)
                    .allowNull(),
            RequiredInput.bool("active", "是否启用", WHY_NO_COLD, "是 / 否"),
            RequiredInput.text("controlBasis", "控制依据", WHY_NO_COLD,
                    "1–" + MAX_REASON + " 字，如工厂确认、法规或产品储存要求", MAX_REASON));

    private final FactoryWarehouseRepository warehouseRepository;

    public WarehousesDraftProposer(FactoryWarehouseRepository warehouseRepository) {
        this.warehouseRepository = warehouseRepository;
    }

    @Override
    public String capabilityId() {
        return WarehouseControlDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        var blueprint = source.blueprint();
        ApplyAIInitRequest.SuggestedData suggested = blueprint.getSourcePayload().getSuggestedData();
        List<WarehouseItem> facts = suggested == null || suggested.getWarehouses() == null
                ? List.of() : suggested.getWarehouses().stream().filter(item -> item != null).toList();
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        List<RequestedWarehouse> warehouses = new ArrayList<>();

        if (facts.isEmpty()) {
            inputs.add(RequiredInput.list("warehouses", "工厂有哪些仓库/存放区",
                    "建厂访谈没有登记存放区",
                    "仓库编号不能重复；仓库和冷链控制点不能都为空（只登记冷藏车时可以不填仓库）", "仓库",
                    0, null, WAREHOUSE_FIELDS));
        } else {
            Map<String, FactoryWarehouse> current = new HashMap<>();
            for (FactoryWarehouse row : warehouseRepository.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(source.factoryId())) {
                current.putIfAbsent(upper(row.getCode()), row);
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < facts.size(); i++) {
                warehouses.add(warehouse(i, facts.get(i), current, seen, blueprint.getVersion(), inputs, evidence));
            }
        }

        inputs.add(RequiredInput.list("coldControls", "有哪些冷链控制点（冷库、冷柜、冷藏车等）",
                "建厂访谈没有问温度和冷链控制",
                "没有冷链控制点就一行都不填；设备编码不能重复", "冷链控制点",
                0, null, COLD_CONTROL_FIELDS));

        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), warehouses, null, null, null, 0L);
        return Proposed.single(capabilityId(), "仓库与冷链控制", request, inputs, evidence);
    }

    private static RequestedWarehouse warehouse(int index, WarehouseItem fact, Map<String, FactoryWarehouse> current,
                                                Set<String> seen, Integer blueprintVersion,
                                                List<RequiredInput> inputs, List<Evidence> evidence) {
        String at = "warehouses[" + index + "].";
        String from = "sourcePayload.suggestedData.warehouses[" + index + "].";
        String name = canonical(fact.getName());
        String shown = name.isBlank() || name.length() > 100 ? "第 " + (index + 1) + " 个存放区" : "“" + name + "”";

        String code = upper(fact.getCode());
        if (fact.getCode() != null) {
            evidence.add(new Evidence(from + "code", fact.getCode()));
        }
        boolean codeUsable = code.matches(CODE_PATTERN) && seen.add(code);
        if (!codeUsable) {
            inputs.add(RequiredInput.text(at + "code", shown + "的仓库编号",
                    code.matches(CODE_PATTERN) ? "访谈里编号 " + code + " 重复出现" : "访谈给的编号不符合仓库编号规则",
                    CODE_CONSTRAINT, MAX_CODE));
            code = null;
        }

        if (name.isBlank() || name.length() > MAX_NAME) {
            inputs.add(RequiredInput.text(at + "name", shown + "的名称",
                    name.isBlank() ? "访谈没有给名称" : "访谈给的名称超过 " + MAX_NAME + " 字",
                    "1–" + MAX_NAME + " 字，现场人员认得的名称", MAX_NAME));
            name = null;
        } else {
            evidence.add(new Evidence(from + "name", name));
        }

        WarehouseType type = parseType(fact.getType());
        if (type == null) {
            inputs.add(RequiredInput.oneOf(at + "type", shown + "是什么类型的仓库",
                    fact.getType() == null || fact.getType().isBlank() ? "访谈没有给仓库类型"
                            : "访谈给的类型“" + fact.getType() + "”不是仓库类型",
                    TYPE_CONSTRAINT, TYPE_OPTIONS));
        } else {
            evidence.add(new Evidence(from + "type", type.name()));
        }

        String remarks = canonical(fact.getRemarks());
        if (remarks.length() > MAX_REMARKS) {
            inputs.add(RequiredInput.text(at + "remarks", shown + "的备注", "访谈给的备注超过 " + MAX_REMARKS + " 字",
                    "不超过 " + MAX_REMARKS + " 字，可以留空", MAX_REMARKS).allowNull());
            remarks = null;
        } else if (!remarks.isBlank()) {
            evidence.add(new Evidence(from + "remarks", remarks));
        }

        DraftOperation operation = null;
        if (code == null) {
            inputs.add(RequiredInput.oneOf(at + "operation", shown + "是新建还是修改已有仓库",
                    "编号待定，无法判断库里是否已有", "CREATE（新编号）或 UPDATE（库里已有的编号）",
                    List.of(CREATE, UPDATE)));
        } else if (current.containsKey(code)) {
            FactoryWarehouse existing = current.get(code);
            inputs.add(RequiredInput.oneOf(at + "operation",
                    "库里已有编号 " + code + " 的仓库“" + existing.getName() + "”，要按蓝图修改它吗",
                    "访谈只登记了存放区，没有说要改库里已有的仓库（旧的建厂流程遇到同编号是跳过不改）",
                    "UPDATE（库里已有该编号时只能修改）；不想修改就把这一项从草稿里删掉",
                    // CREATE on an existing code is refused (WAREHOUSE_DRAFT_CREATE_CONFLICT), so it is not offered.
                    List.of(UPDATE)));
            evidence.add(new Evidence("currentWarehouses." + code, "已存在"));
        } else {
            operation = DraftOperation.CREATE;
        }

        return new RequestedWarehouse(operation, code, name, type,
                // The command refuses planning a deactivation (WAREHOUSE_DRAFT_DEACTIVATION_REQUIRES_INVENTORY_REVIEW).
                true,
                remarks,
                "依据建厂蓝图 v" + blueprintVersion + "：访谈登记的存放区" + shown);
    }

    private static boolean deprecated(WarehouseType type) {
        try {
            return WarehouseType.class.getField(type.name()).isAnnotationPresent(Deprecated.class);
        } catch (NoSuchFieldException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static WarehouseType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WarehouseType.valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
