package com.cretas.aims.service.warehousecontroldraft;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraft;
import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraftColdControl;
import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraftItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import com.cretas.aims.repository.warehousecontrol.WarehouseControlDraftColdControlRepository;
import com.cretas.aims.repository.warehousecontrol.WarehouseControlDraftItemRepository;
import com.cretas.aims.repository.warehousecontrol.WarehouseControlDraftRepository;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.ColdControlType;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftIssue;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftOperation;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftPreview;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftReadback;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftReceipt;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftRequest;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.RecoveryPlan;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.RequestedColdControl;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.RequestedWarehouse;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.ResolvedColdControl;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.ResolvedWarehouse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Module-owned warehouse and cold-chain control DRAFT command.
 *
 * <p>It reads existing warehouse/equipment master data only and writes three isolated DRAFT
 * tables. It never calls warehouse writers, changes inventory quantities/batches/receipts, records
 * temperatures, creates alerts, publishes, activates or controls equipment.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class WarehouseControlDraftCommand {

    public static final String CAPABILITY_ID = "warehouses";
    public static final String REQUIRED_PERMISSION = "warehouse:draft:write";
    private static final BigDecimal ABSOLUTE_ZERO = new BigDecimal("-273.15");
    private static final BigDecimal MAX_SUPPORTED_CELSIUS = new BigDecimal("100.00");

    private final WarehouseControlDraftRepository draftRepository;
    private final WarehouseControlDraftItemRepository itemRepository;
    private final WarehouseControlDraftColdControlRepository coldControlRepository;
    private final FactoryWarehouseRepository warehouseRepository;
    private final ColdChainEquipmentRepository equipmentRepository;

    public WarehouseControlDraftCommand(
            WarehouseControlDraftRepository draftRepository,
            WarehouseControlDraftItemRepository itemRepository,
            WarehouseControlDraftColdControlRepository coldControlRepository,
            FactoryWarehouseRepository warehouseRepository,
            ColdChainEquipmentRepository equipmentRepository) {
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository");
        this.itemRepository = Objects.requireNonNull(itemRepository, "itemRepository");
        this.coldControlRepository = Objects.requireNonNull(coldControlRepository, "coldControlRepository");
        this.warehouseRepository = Objects.requireNonNull(warehouseRepository, "warehouseRepository");
        this.equipmentRepository = Objects.requireNonNull(equipmentRepository, "equipmentRepository");
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        Map<String, FactoryWarehouse> currentWarehouses = currentWarehouses(request.factoryId(), issues);
        List<ResolvedWarehouse> warehouses = resolveWarehouses(request, currentWarehouses, issues);
        Set<String> usableWarehouseCodes = new HashSet<>();
        currentWarehouses.values().stream().filter(row -> Boolean.TRUE.equals(row.getIsActive()))
                .map(row -> upper(row.getCode())).forEach(usableWarehouseCodes::add);
        warehouses.stream().filter(ResolvedWarehouse::active).map(ResolvedWarehouse::code)
                .forEach(usableWarehouseCodes::add);
        List<ResolvedColdControl> coldControls = resolveColdControls(
                request, usableWarehouseCodes, issues);

        boolean hasRequestedWork = !request.warehouses().isEmpty() || !request.coldControls().isEmpty();
        if (!hasRequestedWork) {
            issues.add(issue("WAREHOUSE_CONTROL_DRAFT_EMPTY", "request",
                    "仓库控制草稿至少要包含一个仓库或冷链控制点",
                    "确认本次要新增或修改的仓库/冷链控制点"));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        boolean dependencyClosureComplete = hasRequestedWork && issues.isEmpty();
        String sourceSnapshotDigest = sourceSnapshotDigest(currentWarehouses, warehouses, coldControls);
        String desiredDigest = desiredDigest(warehouses, coldControls);
        String status = dependencyClosureComplete ? "READY" : "BLOCKED";
        String previewDigest = digest(WarehouseControlDraftModels.SCHEMA_VERSION, request.factoryId(),
                request.blueprintId(), String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                sourceSnapshotDigest, desiredDigest, String.valueOf(dependencyClosureComplete), status,
                issueDigest(issues));
        return new DraftPreview(WarehouseControlDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                List.of("factory"), dependencyClosureComplete, WarehouseControlDraftModels.DEPENDENCY_BASIS,
                warehouses, coldControls, warehouses.size(), coldControls.size(), sourceSnapshotDigest,
                desiredDigest, previewDigest, issues, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.AuthenticatedOperator operator) {
        if (operator == null
                || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN)
                || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "建厂草稿操作人身份与工厂范围不匹配", "DRAFT_OPERATOR_SCOPE_MISMATCH",
                    "使用当前工厂的有效工厂用户身份重试", "operatorId");
        }
        return materialize(request, operator.permissions(), operator.actor());
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, Set<String> operatorPermissions) {
        return materialize(request, operatorPermissions, new com.cretas.aims.security.ActorRef(
                com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, request.operatorId()));
    }

    private DraftReceipt materialize(DraftRequest request, Set<String> operatorPermissions,
            com.cretas.aims.security.ActorRef actor) {
        validateEnvelope(request, true);
        if (operatorPermissions == null || !operatorPermissions.contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少仓库控制草稿权限", "WAREHOUSE_CONTROL_DRAFT_PERMISSION_REQUIRED",
                    "由仓储配置负责人授予 warehouse:draft:write 后重试", "permission");
        }
        String requestDigest = requestDigest(request, actor);
        Optional<WarehouseControlDraft> prior = draftRepository.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestDigest);

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            DraftIssue first = preview.issues().isEmpty() ? null : preview.issues().get(0);
            throw invalid(409, "仓库控制草稿预览仍有阻断项", "WAREHOUSE_CONTROL_DRAFT_PREVIEW_BLOCKED",
                    first == null ? "重新生成完整预览" : first.recovery(),
                    first == null ? "request" : first.target());
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "仓库控制草稿预览摘要已变化", "WAREHOUSE_CONTROL_DRAFT_PREVIEW_DIGEST_CONFLICT",
                    "刷新当前仓库和冷链设备状态后重新查看并确认 ChangeSet", "confirmedPreviewDigest");
        }

        String draftId = UUID.randomUUID().toString();
        WarehouseControlDraft parent = WarehouseControlDraft.builder()
                .id(draftId).factoryId(request.factoryId()).blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion()).sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest()).desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest()).requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey()).operatorId(actor.factoryUserId()).actorType(actor.type()).actorId(actor.id())
                .warehouseCount(preview.warehouseCount()).coldControlCount(preview.coldControlCount())
                .dependencyBasis(WarehouseControlDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true).status("DRAFT").build();
        WarehouseControlDraft saved;
        try {
            saved = draftRepository.saveAndFlush(parent);
        } catch (DataIntegrityViolationException race) {
            if (!isScopedIdempotencyRace(race)) throw race;
            throw invalid(409, "仓库控制草稿幂等键发生并发竞争", "WAREHOUSE_CONTROL_DRAFT_IDEMPOTENCY_RACE",
                    "使用相同幂等键重试以读取胜出回执", "idempotencyKey");
        }

        List<WarehouseControlDraftItem> items = new ArrayList<>();
        for (ResolvedWarehouse value : preview.warehouses()) {
            items.add(WarehouseControlDraftItem.builder()
                        .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(request.factoryId())
                        .ordinal(value.ordinal()).operation(value.operation().name())
                        .targetWarehouseId(value.targetWarehouseId()).warehouseCode(value.code())
                        .warehouseName(value.name()).warehouseType(value.type().name())
                        .desiredActive(value.active()).remarks(value.remarks()).changeReason(value.changeReason())
                        .currentSnapshotDigest(value.currentSnapshotDigest()).build());
        }
        List<WarehouseControlDraftColdControl> controls = new ArrayList<>();
        for (ResolvedColdControl value : preview.coldControls()) {
            controls.add(WarehouseControlDraftColdControl.builder()
                        .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(request.factoryId())
                        .ordinal(value.ordinal()).operation(value.operation().name())
                        .targetEquipmentId(value.targetEquipmentId()).equipmentCode(value.equipmentCode())
                        .equipmentName(value.equipmentName()).controlType(value.type().name())
                        .warehouseCode(blankToNull(value.warehouseCode()))
                        .targetTempMinCelsius(value.targetTempMinCelsius())
                        .targetTempMaxCelsius(value.targetTempMaxCelsius())
                        .toleranceMinutes(value.toleranceMinutes()).location(blankToNull(value.location()))
                        .desiredActive(value.active()).controlBasis(value.controlBasis())
                        .currentSnapshotDigest(value.currentSnapshotDigest())
                        .temperatureUnit(WarehouseControlDraftModels.TEMPERATURE_UNIT)
                        .deviationAlgorithm(WarehouseControlDraftModels.DEVIATION_ALGORITHM).build());
        }
        List<WarehouseControlDraftItem> savedItems = itemRepository.saveAllAndFlush(items);
        List<WarehouseControlDraftColdControl> savedControls = coldControlRepository.saveAllAndFlush(controls);
        requireExactReadback(saved, savedItems, savedControls, preview);
        return receipt(saved, false, 1, 1 + savedItems.size() + savedControls.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        WarehouseControlDraft parent = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该仓库控制草稿",
                        "WAREHOUSE_CONTROL_DRAFT_NOT_FOUND", "刷新当前工厂的仓库控制草稿回执", "draftId"));
        List<ResolvedWarehouse> warehouses = reconstructWarehouses(itemRepository
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), parent.getFactoryId()));
        List<ResolvedColdControl> controls = reconstructControls(coldControlRepository
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), parent.getFactoryId()));
        if (warehouses.size() != parent.getWarehouseCount() || controls.size() != parent.getColdControlCount()) {
            throw readbackMismatch();
        }
        return new DraftReadback(WarehouseControlDraftModels.SCHEMA_VERSION, parent.getFactoryId(), parent.getId(),
                parent.getStatus(), warehouses, controls, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        WarehouseControlDraft row = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该仓库控制草稿",
                        "WAREHOUSE_CONTROL_DRAFT_NOT_FOUND", "刷新当前工厂的仓库控制草稿回执", "draftId"));
        return new RecoveryPlan(WarehouseControlDraftModels.SCHEMA_VERSION, row.getFactoryId(), row.getId(),
                row.getDesiredDigest(), "SOFT_DELETE_DRAFT_AFTER_WAREHOUSE_OWNER_CONFIRMATION",
                "只移除未发布草稿；不会改变仓库、库存、批次、收货、冷链设备、温度读数或告警",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Map<String, FactoryWarehouse> currentWarehouses(String factoryId, List<DraftIssue> issues) {
        Map<String, FactoryWarehouse> out = new LinkedHashMap<>();
        for (FactoryWarehouse row : warehouseRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(factoryId)) {
            String code = upper(row.getCode());
            if (out.putIfAbsent(code, row) != null) {
                issues.add(issue("WAREHOUSE_CURRENT_CODE_AMBIGUOUS", "currentWarehouses." + code,
                        "当前工厂存在大小写归一后重复的仓库编号", "先由仓库负责人治理正式主数据"));
            }
        }
        return out;
    }

    private List<ResolvedWarehouse> resolveWarehouses(
            DraftRequest request,
            Map<String, FactoryWarehouse> current,
            List<DraftIssue> issues) {
        List<ResolvedWarehouse> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.warehouses().size(); i++) {
            RequestedWarehouse value = request.warehouses().get(i);
            String target = "warehouses[" + i + "]";
            if (value == null) {
                issues.add(issue("WAREHOUSE_DRAFT_ITEM_REQUIRED", target, "仓库草稿项不能为空", "删除空项或补齐仓库信息"));
                continue;
            }
            if (value.operation() == null) {
                issues.add(issue("WAREHOUSE_DRAFT_OPERATION_REQUIRED", target + ".operation",
                        "必须明确新增还是修改仓库", "选择 CREATE 或 UPDATE"));
            }
            if (!value.code().matches("[A-Z0-9][A-Z0-9_-]{1,31}")) {
                issues.add(issue("WAREHOUSE_DRAFT_CODE_INVALID", target + ".code",
                        "仓库编号必须为 2-32 位大写字母、数字、下划线或连字符", "使用稳定的工厂内仓库编号"));
            } else if (!seen.add(value.code())) {
                issues.add(issue("WAREHOUSE_DRAFT_CODE_DUPLICATED", target + ".code",
                        "同一草稿不能重复配置仓库 " + value.code(), "每个仓库编号只保留一项"));
            }
            if (value.name().isBlank() || value.name().length() > 100) {
                issues.add(issue("WAREHOUSE_DRAFT_NAME_INVALID", target + ".name",
                        "仓库名称不能为空且不能超过 100 字符", "填写工厂人员能够识别的仓库名称"));
            }
            if (value.type() == null) {
                issues.add(issue("WAREHOUSE_DRAFT_TYPE_REQUIRED", target + ".type",
                        "仓库阶段类型不能为空", "从服务器返回的 WarehouseType 中选择"));
            }
            if (!Boolean.TRUE.equals(value.active())) {
                issues.add(issue("WAREHOUSE_DRAFT_DEACTIVATION_REQUIRES_INVENTORY_REVIEW", target + ".active",
                        "A5 不允许直接规划停用仓库", "先独立证明库存、批次、在途和默认路由已清空，再走停用审批"));
            }
            if (value.remarks().length() > 1000 || value.changeReason().isBlank()
                    || value.changeReason().length() > 500) {
                issues.add(issue("WAREHOUSE_DRAFT_REASON_INVALID", target + ".changeReason",
                        "变更理由不能为空且不能超过 500 字符，备注不能超过 1000 字符", "保留仓库业务用途和变更依据"));
            }
            FactoryWarehouse existing = current.get(value.code());
            if (value.operation() == DraftOperation.CREATE && existing != null) {
                issues.add(issue("WAREHOUSE_DRAFT_CREATE_CONFLICT", target + ".code",
                        "仓库编号已存在，不能按 CREATE 处理", "改为 UPDATE 并重新确认当前快照"));
            }
            if (value.operation() == DraftOperation.UPDATE && existing == null) {
                issues.add(issue("WAREHOUSE_DRAFT_UPDATE_TARGET_MISSING", target + ".code",
                        "要修改的仓库不存在或不属于当前工厂", "刷新仓库目录后重新选择"));
            }
            if (value.operation() == null || value.type() == null || value.code().isBlank()
                    || value.name().isBlank()) continue;
            out.add(new ResolvedWarehouse(out.size(), value.operation(), existing == null ? null : existing.getId(),
                    value.code(), value.name(), value.type(), Boolean.TRUE.equals(value.active()), value.remarks(),
                    value.changeReason(), warehouseSnapshot(existing)));
        }
        return out;
    }

    private List<ResolvedColdControl> resolveColdControls(
            DraftRequest request,
            Set<String> usableWarehouseCodes,
            List<DraftIssue> issues) {
        List<ResolvedColdControl> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.coldControls().size(); i++) {
            RequestedColdControl value = request.coldControls().get(i);
            String target = "coldControls[" + i + "]";
            if (value == null) {
                issues.add(issue("COLD_CONTROL_DRAFT_ITEM_REQUIRED", target,
                        "冷链控制点不能为空", "删除空项或补齐控制点"));
                continue;
            }
            if (value.operation() == null) {
                issues.add(issue("COLD_CONTROL_DRAFT_OPERATION_REQUIRED", target + ".operation",
                        "必须明确新增还是修改冷链控制点", "选择 CREATE 或 UPDATE"));
            }
            if (!value.equipmentCode().matches("[A-Z0-9][A-Z0-9_-]{1,49}")) {
                issues.add(issue("COLD_CONTROL_DRAFT_CODE_INVALID", target + ".equipmentCode",
                        "设备编码必须为 2-50 位大写字母、数字、下划线或连字符", "使用稳定的工厂内设备编码"));
            } else if (!seen.add(value.equipmentCode())) {
                issues.add(issue("COLD_CONTROL_DRAFT_CODE_DUPLICATED", target + ".equipmentCode",
                        "同一草稿不能重复配置冷链控制点", "每个设备编码只保留一项"));
            }
            if (value.equipmentName().isBlank() || value.equipmentName().length() > 200) {
                issues.add(issue("COLD_CONTROL_DRAFT_NAME_INVALID", target + ".equipmentName",
                        "冷链控制点名称不能为空且不能超过 200 字符", "填写现场可识别的设备/控制点名称"));
            }
            if (value.type() == null) {
                issues.add(issue("COLD_CONTROL_DRAFT_TYPE_REQUIRED", target + ".type",
                        "冷链控制类型不能为空", "选择服务器封闭的冷链控制类型"));
            }
            boolean vehicle = value.type() == ColdControlType.COLD_CHAIN_VEHICLE;
            if (!value.warehouseCode().isBlank() && !usableWarehouseCodes.contains(value.warehouseCode())) {
                issues.add(issue("COLD_CONTROL_DRAFT_WAREHOUSE_MISSING", target + ".warehouseCode",
                        "关联仓库不存在、未启用或不属于当前工厂", "选择当前启用仓库或本草稿内新建仓库"));
            }
            if (!vehicle && value.warehouseCode().isBlank()) {
                issues.add(issue("COLD_CONTROL_DRAFT_WAREHOUSE_REQUIRED", target + ".warehouseCode",
                        "非冷链车辆控制点必须绑定仓库", "选择控制点所在仓库"));
            }
            validateTemperature(value, target, issues);
            if (value.location().length() > 200 || value.controlBasis().isBlank()
                    || value.controlBasis().length() > 500 || value.active() == null) {
                issues.add(issue("COLD_CONTROL_DRAFT_BASIS_INVALID", target + ".controlBasis",
                        "启用状态和控制依据必填；依据不超过 500 字符，位置不超过 200 字符",
                        "记录工厂确认、法规或产品储存要求"));
            }
            ColdChainEquipment existing = equipmentRepository
                    .findByFactoryIdAndEquipmentCode(request.factoryId(), value.equipmentCode()).orElse(null);
            if (value.operation() == DraftOperation.CREATE && existing != null) {
                issues.add(issue("COLD_CONTROL_DRAFT_CREATE_CONFLICT", target + ".equipmentCode",
                        "冷链设备编码已存在，不能按 CREATE 处理", "改为 UPDATE 并重新确认当前快照"));
            }
            if (value.operation() == DraftOperation.UPDATE && existing == null) {
                issues.add(issue("COLD_CONTROL_DRAFT_UPDATE_TARGET_MISSING", target + ".equipmentCode",
                        "要修改的冷链控制点不存在或不属于当前工厂", "刷新设备目录后重新选择"));
            }
            if (value.operation() == null || value.type() == null || value.equipmentCode().isBlank()
                    || value.equipmentName().isBlank() || value.targetTempMinCelsius() == null
                    || value.targetTempMaxCelsius() == null || value.toleranceMinutes() == null) continue;
            out.add(new ResolvedColdControl(out.size(), value.operation(), existing == null ? null : existing.getId(),
                    value.equipmentCode(), value.equipmentName(), value.type(), value.warehouseCode(),
                    value.targetTempMinCelsius(), value.targetTempMaxCelsius(), value.toleranceMinutes(),
                    value.location(), Boolean.TRUE.equals(value.active()), value.controlBasis(),
                    equipmentSnapshot(existing), WarehouseControlDraftModels.TEMPERATURE_UNIT,
                    WarehouseControlDraftModels.DEVIATION_ALGORITHM));
        }
        return out;
    }

    private void validateTemperature(RequestedColdControl value, String target, List<DraftIssue> issues) {
        BigDecimal min = value.targetTempMinCelsius();
        BigDecimal max = value.targetTempMaxCelsius();
        if (min == null || max == null || min.compareTo(ABSOLUTE_ZERO) < 0
                || max.compareTo(MAX_SUPPORTED_CELSIUS) > 0 || min.compareTo(max) > 0) {
            issues.add(issue("COLD_CONTROL_DRAFT_TEMPERATURE_RANGE_INVALID", target + ".temperatureRange",
                    "温度上下限必须是 -273.15°C 到 100°C 内的有效闭区间",
                    "确认储存要求并填写最小值不大于最大值"));
        }
        if (value.toleranceMinutes() == null || value.toleranceMinutes() < 1
                || value.toleranceMinutes() > 1440) {
            issues.add(issue("COLD_CONTROL_DRAFT_TOLERANCE_INVALID", target + ".toleranceMinutes",
                    "持续偏离容忍时间必须为 1-1440 分钟", "由工厂确认持续偏离多久后进入告警处理"));
        }
    }

    private List<ResolvedWarehouse> reconstructWarehouses(List<WarehouseControlDraftItem> rows) {
        return rows.stream().map(row -> new ResolvedWarehouse(row.getOrdinal(),
                DraftOperation.valueOf(row.getOperation()), row.getTargetWarehouseId(), row.getWarehouseCode(),
                row.getWarehouseName(), FactoryWarehouse.WarehouseType.valueOf(row.getWarehouseType()),
                Boolean.TRUE.equals(row.getDesiredActive()), clean(row.getRemarks()), row.getChangeReason(),
                row.getCurrentSnapshotDigest())).toList();
    }

    private List<ResolvedColdControl> reconstructControls(List<WarehouseControlDraftColdControl> rows) {
        return rows.stream().map(row -> new ResolvedColdControl(row.getOrdinal(),
                DraftOperation.valueOf(row.getOperation()), row.getTargetEquipmentId(), row.getEquipmentCode(),
                row.getEquipmentName(), ColdControlType.valueOf(row.getControlType()), clean(row.getWarehouseCode()),
                canonical(row.getTargetTempMinCelsius()), canonical(row.getTargetTempMaxCelsius()),
                row.getToleranceMinutes(), clean(row.getLocation()), Boolean.TRUE.equals(row.getDesiredActive()),
                row.getControlBasis(), row.getCurrentSnapshotDigest(), row.getTemperatureUnit(),
                row.getDeviationAlgorithm())).toList();
    }

    private void requireExactReadback(
            WarehouseControlDraft parent,
            List<WarehouseControlDraftItem> items,
            List<WarehouseControlDraftColdControl> controls,
            DraftPreview preview) {
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !preview.factoryId().equals(parent.getFactoryId())
                || !preview.sourceSnapshotDigest().equals(parent.getSourceSnapshotDigest())
                || !preview.desiredDigest().equals(parent.getDesiredDigest())
                || !preview.previewDigest().equals(parent.getPreviewDigest())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || items.size() != preview.warehouseCount() || controls.size() != preview.coldControlCount()) {
            throw readbackMismatch();
        }
        List<ResolvedWarehouse> foundWarehouses = reconstructWarehouses(items.stream()
                .sorted(Comparator.comparing(WarehouseControlDraftItem::getOrdinal)).toList());
        List<ResolvedColdControl> foundControls = reconstructControls(controls.stream()
                .sorted(Comparator.comparing(WarehouseControlDraftColdControl::getOrdinal)).toList());
        if (!preview.warehouses().equals(foundWarehouses) || foundControls.size() != preview.coldControls().size()) {
            throw readbackMismatch();
        }
        for (int i = 0; i < foundControls.size(); i++) {
            ResolvedColdControl expected = preview.coldControls().get(i);
            ResolvedColdControl found = foundControls.get(i);
            if (expected.ordinal() != found.ordinal() || expected.operation() != found.operation()
                    || !Objects.equals(expected.targetEquipmentId(), found.targetEquipmentId())
                    || !expected.equipmentCode().equals(found.equipmentCode())
                    || !expected.equipmentName().equals(found.equipmentName()) || expected.type() != found.type()
                    || !expected.warehouseCode().equals(found.warehouseCode())
                    || expected.targetTempMinCelsius().compareTo(found.targetTempMinCelsius()) != 0
                    || expected.targetTempMaxCelsius().compareTo(found.targetTempMaxCelsius()) != 0
                    || expected.toleranceMinutes() != found.toleranceMinutes()
                    || !expected.location().equals(found.location()) || expected.active() != found.active()
                    || !expected.controlBasis().equals(found.controlBasis())
                    || !expected.currentSnapshotDigest().equals(found.currentSnapshotDigest())
                    || !WarehouseControlDraftModels.TEMPERATURE_UNIT.equals(found.temperatureUnit())
                    || !WarehouseControlDraftModels.DEVIATION_ALGORITHM.equals(found.deviationAlgorithm())) {
                throw readbackMismatch();
            }
        }
    }

    private BusinessException readbackMismatch() {
        return invalid(409, "仓库控制草稿回读与已确认 ChangeSet 不一致",
                "WAREHOUSE_CONTROL_DRAFT_READBACK_MISMATCH", "保留事务回滚并重新预览", "draft");
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null) throw invalid(400, "仓库控制草稿请求不能为空",
                "WAREHOUSE_CONTROL_DRAFT_REQUEST_REQUIRED", "从已确认的 warehouses ChangeSet 生成请求", "request");
        if (request.factoryId().isBlank() || request.blueprintId().isBlank() || request.blueprintVersion() <= 0) {
            throw invalid(400, "工厂与 Blueprint 版本不能为空", "WAREHOUSE_CONTROL_DRAFT_SCOPE_REQUIRED",
                    "锁定 factoryId、blueprintId 和 blueprintVersion", "blueprint");
        }
        if (!request.sourceDigest().matches("[0-9a-f]{64}")) {
            throw invalid(400, "Blueprint sourceDigest 必须是 SHA-256", "WAREHOUSE_CONTROL_DRAFT_SOURCE_INVALID",
                    "使用服务器编译工件的 sourceDigest", "sourceDigest");
        }
        if (materialize) {
            if (request.operatorId() <= 0) throw invalid(400, "仓库控制草稿必须记录操作人",
                    "WAREHOUSE_CONTROL_DRAFT_OPERATOR_REQUIRED", "使用已认证仓储配置负责人身份", "operatorId");
            if (request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 128) {
                throw invalid(400, "仓库控制草稿幂等键不能为空且不能超过 128 字符",
                        "WAREHOUSE_CONTROL_DRAFT_IDEMPOTENCY_INVALID", "为本次确认生成稳定幂等键", "idempotencyKey");
            }
            if (!request.confirmedPreviewDigest().matches("[0-9a-f]{64}")) {
                throw invalid(400, "必须确认服务器生成的 previewDigest",
                        "WAREHOUSE_CONTROL_DRAFT_CONFIRMED_PREVIEW_REQUIRED", "重新预览并确认当前摘要",
                        "confirmedPreviewDigest");
            }
        }
    }

    private DraftReceipt replay(WarehouseControlDraft row, String requestDigest) {
        if (!row.getRequestDigest().equals(requestDigest)) {
            throw invalid(409, "该幂等键已绑定另一份仓库控制草稿载荷",
                    "WAREHOUSE_CONTROL_DRAFT_IDEMPOTENCY_KEY_CONFLICT", "复用原请求或生成新幂等键", "idempotencyKey");
        }
        return receipt(row, true, 0, 1 + row.getWarehouseCount() + row.getColdControlCount());
    }

    private DraftReceipt receipt(WarehouseControlDraft row, boolean replayed, int writes, int persistedRows) {
        return new DraftReceipt(WarehouseControlDraftModels.SCHEMA_VERSION, row.getId(), row.getFactoryId(), row.getId(),
                row.getStatus(), row.getSourceSnapshotDigest(), row.getDesiredDigest(), row.getPreviewDigest(),
                row.getRequestDigest(), row.getWarehouseCount(), row.getColdControlCount(),
                Boolean.TRUE.equals(row.getDependencyClosureComplete()), Objects.requireNonNullElse(row.getLockVersion(), 0L),
                replayed, writes, 1, persistedRows, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "由仓储负责人确认后软删除整份 DRAFT；正式仓库、库存、批次、收货与冷链运行记录不受影响");
    }

    private String sourceSnapshotDigest(
            Map<String, FactoryWarehouse> current,
            List<ResolvedWarehouse> warehouses,
            List<ResolvedColdControl> controls) {
        List<String> values = new ArrayList<>();
        current.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            values.add(entry.getKey());
            values.add(warehouseSnapshot(entry.getValue()));
        });
        warehouses.forEach(value -> values.add(value.currentSnapshotDigest()));
        controls.forEach(value -> values.add(value.currentSnapshotDigest()));
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(List<ResolvedWarehouse> warehouses, List<ResolvedColdControl> controls) {
        List<String> values = new ArrayList<>();
        warehouses.forEach(value -> {
            values.add(value.operation().name()); values.add(value.code()); values.add(value.name());
            values.add(value.type().name()); values.add(String.valueOf(value.active())); values.add(value.remarks());
            values.add(value.changeReason());
        });
        controls.forEach(value -> {
            values.add(value.operation().name()); values.add(value.equipmentCode()); values.add(value.equipmentName());
            values.add(value.type().name()); values.add(value.warehouseCode());
            values.add(decimal(value.targetTempMinCelsius())); values.add(decimal(value.targetTempMaxCelsius()));
            values.add(String.valueOf(value.toleranceMinutes())); values.add(value.location());
            values.add(String.valueOf(value.active())); values.add(value.controlBasis());
            values.add(WarehouseControlDraftModels.TEMPERATURE_UNIT);
            values.add(WarehouseControlDraftModels.DEVIATION_ALGORITHM);
        });
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, com.cretas.aims.security.ActorRef actor) {
        List<String> values = new ArrayList<>(List.of(WarehouseControlDraftModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest()));
        request.warehouses().stream().filter(Objects::nonNull).sorted(Comparator.comparing(RequestedWarehouse::code))
                .forEach(value -> {
                    values.add(String.valueOf(value.operation())); values.add(value.code()); values.add(value.name());
                    values.add(String.valueOf(value.type())); values.add(String.valueOf(value.active()));
                    values.add(value.remarks()); values.add(value.changeReason());
                });
        request.coldControls().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(RequestedColdControl::equipmentCode)).forEach(value -> {
                    values.add(String.valueOf(value.operation())); values.add(value.equipmentCode());
                    values.add(value.equipmentName()); values.add(String.valueOf(value.type()));
                    values.add(value.warehouseCode()); values.add(decimal(value.targetTempMinCelsius()));
                    values.add(decimal(value.targetTempMaxCelsius())); values.add(String.valueOf(value.toleranceMinutes()));
                    values.add(value.location()); values.add(String.valueOf(value.active())); values.add(value.controlBasis());
                });
        values.add(request.confirmedPreviewDigest()); values.add(request.idempotencyKey());
        values.add(actor.type().name() + ":" + String.valueOf(actor.id()));
        return digest(values.toArray(String[]::new));
    }

    private String warehouseSnapshot(FactoryWarehouse row) {
        if (row == null) return digest("ABSENT", "WAREHOUSE");
        return digest("WAREHOUSE", row.getId(), row.getFactoryId(), upper(row.getCode()), clean(row.getName()),
                String.valueOf(row.getType()), String.valueOf(row.getIsActive()), clean(row.getRemarks()),
                String.valueOf(row.getUpdatedAt()));
    }

    private String equipmentSnapshot(ColdChainEquipment row) {
        if (row == null) return digest("ABSENT", "COLD_CONTROL");
        return digest("COLD_CONTROL", String.valueOf(row.getId()), row.getFactoryId(), upper(row.getEquipmentCode()),
                clean(row.getEquipmentName()), clean(row.getType()), decimal(row.getTargetTempMin()),
                decimal(row.getTargetTempMax()), clean(row.getUnit()), String.valueOf(row.getToleranceMinutes()),
                clean(row.getLocation()), String.valueOf(row.isActive()), String.valueOf(row.getUpdatedAt()));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(value -> value.code() + "\u0000" + value.target()
                + "\u0000" + value.message()).toArray(String[]::new));
    }

    private boolean isScopedIdempotencyRace(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (clean(cursor.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_warehouse_control_draft_idempotency")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = clean(value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal canonical(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static String clean(String value) {
        return WarehouseControlDraftModels.clean(value);
    }

    private static String upper(String value) {
        return clean(value).toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private static BusinessException invalid(int status, String message, String code, String recovery, String target) {
        return new BusinessException(status, message).withCode(code).withHint(recovery)
                .withHintTarget(target).withSeverity("warning");
    }
}
