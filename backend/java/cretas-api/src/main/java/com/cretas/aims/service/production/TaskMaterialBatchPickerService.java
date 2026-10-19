package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.TaskMaterialBatchOptionDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import com.cretas.aims.service.yield.impl.YieldUnitResolver;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.utils.ReportAuthGuard;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Collectors;

/** Read-only, task-bound material candidates. It deliberately has no inventory fallback. */
@Service
@RequiredArgsConstructor
public class TaskMaterialBatchPickerService {
    private record InputUnit(String code, java.util.Set<String> frozenDisplays) {
        boolean matches(String requested) {
            return requested != null && (frozenDisplays.contains(requested.trim())
                    || Objects.equals(code, UnitContractServiceImpl.crossLanguageCode(requested)));
        }
    }

    private record NativeRef(MaterialBatchRef target, java.math.BigDecimal quantity, String unit) {}

    private final WorkProcessTaskRepository taskRepository;
    private final ProductionBatchRepository batchRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final ProductWorkProcessAssigneeRepository assigneeRepository;
    private final WorkflowTaskPortRepository taskPortRepository;
    private final WarehouseResolver warehouseResolver;

    @Transactional(readOnly = true)
    public List<TaskMaterialBatchOptionDTO> list(String factoryId, Long batchId, Long taskId) {
        WorkProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, taskId)
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在"));
        if (!factoryId.equals(task.getFactoryId())) {
            throw new BusinessException(403, "工序任务不属于当前工厂");
        }
        if (!batchId.equals(task.getProductionBatchId())) {
            throw new BusinessException(409, "工序任务不属于当前生产批次")
                    .withHint("请从任务所属批次重新打开领料选择");
        }
        assertActorCanReport(task);

        batchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产批次不存在"));
        Map<String, InputUnit> materialTypes = taskMaterialTypes(factoryId, task);
        if (materialTypes.isEmpty()) {
            throw new BusinessException(409, "当前任务没有可用的原料输入端口快照")
                    .withHint("请让主管补齐当前任务的 INPUT 原料端口后重试");
        }
        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        return materialTypes.keySet().stream()
                .flatMap(sku -> materialBatchRepository.findAvailableBatchesFEFOByWarehouse(factoryId, sku, workshopId).stream())
                .filter(batch -> factoryId.equals(batch.getFactoryId()) && workshopId.equals(batch.getWarehouseId())
                        && batch.getStatus() == MaterialBatchStatus.AVAILABLE
                        && materialTypes.containsKey(batch.getMaterialTypeId()))
                .filter(batch -> batch.getOwnership() == null || batch.getOwnership() == InventoryOwnership.COMPANY_OWNED)
                .filter(batch -> !"PRODUCTION_BATCH".equalsIgnoreCase(batch.getSourceDocType()))
                .filter(batch -> batch.getCurrentQuantity() != null
                        && batch.getCurrentQuantity().signum() > 0)
                .collect(Collectors.toMap(MaterialBatch::getId, b -> b, (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .map(batch -> toOption(batch, materialTypes.get(batch.getMaterialTypeId())))
                .filter(Objects::nonNull)
                .toList();
    }

    /** Reusable write-side guard for the report worker; it performs no inventory mutation. */
    public java.math.BigDecimal validateMaterialBatchRefs(String factoryId, Long batchId, Long taskId,
                                          List<MaterialBatchRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        WorkProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, taskId)
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在"));
        if (!factoryId.equals(task.getFactoryId())) {
            throw new BusinessException(403, "工序任务不属于当前工厂");
        }
        if (!batchId.equals(task.getProductionBatchId())) {
            throw new BusinessException(409, "工序任务不属于当前生产批次");
        }
        assertActorCanReport(task);
        Map<String, InputUnit> allowedTypes = taskMaterialTypes(factoryId, task);
        if (allowedTypes.isEmpty()) {
            throw new BusinessException(409, "当前任务没有可用的原料输入端口快照");
        }
        if (allowedTypes.values().stream().map(InputUnit::code).distinct().count() != 1) {
            throw new BusinessException(409, "原料输入端口量纲不同，不能合并为单一投入数量");
        }
        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        Map<String, java.math.BigDecimal> totals = new LinkedHashMap<>();
        List<NativeRef> nativeRefs = new java.util.ArrayList<>();
        java.math.BigDecimal taskQuantity = java.math.BigDecimal.ZERO;
        for (MaterialBatchRef ref : refs) {
            if (ref == null || ref.getMaterialBatchId() == null || ref.getMaterialBatchId().isBlank()
                    || ref.getQuantity() == null || ref.getQuantity().signum() <= 0
                    || ref.getUnit() == null || ref.getUnit().isBlank()) {
                throw new BusinessException(400, "领料批次引用必须包含正数量和单位");
            }
            MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(ref.getMaterialBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(403, "原料批次不属于当前工厂"));
            if (!factoryId.equals(batch.getFactoryId()) || !ref.getMaterialBatchId().equals(batch.getId())
                    || batch.getStatus() != MaterialBatchStatus.AVAILABLE
                    || !workshopId.equals(batch.getWarehouseId())
                    || batch.getOwnership() != null && batch.getOwnership() != InventoryOwnership.COMPANY_OWNED
                    || "PRODUCTION_BATCH".equalsIgnoreCase(batch.getSourceDocType())
                    || batch.getCurrentQuantity() == null || batch.getCurrentQuantity().signum() <= 0
                    || !allowedTypes.containsKey(batch.getMaterialTypeId())) {
                throw new BusinessException(409, "所选原料批次不属于当前任务的可用输入");
            }
            InputUnit inputPort = allowedTypes.get(batch.getMaterialTypeId());
            String portUnit = inputPort.code();
            String refUnit = inputPort.matches(ref.getUnit()) ? portUnit
                    : UnitContractServiceImpl.crossLanguageCode(ref.getUnit());
            String batchUnit = inputPort.matches(batch.getQuantityUnit()) ? portUnit
                    : UnitContractServiceImpl.crossLanguageCode(batch.getQuantityUnit());
            java.math.BigDecimal nativeQuantity = refUnit == null || batchUnit == null ? null
                    : FgQuantityUnitConverter.convert(ref.getQuantity(), refUnit, batchUnit, null);
            if (nativeQuantity == null || portUnit == null
                    || FgQuantityUnitConverter.convert(java.math.BigDecimal.ONE, batchUnit, portUnit, null) == null) {
                throw new BusinessException(409, "领料单位与任务输入端口不一致，缺少可验证的换算依据");
            }
            totals.merge(batch.getId(), nativeQuantity, java.math.BigDecimal::add);
            if (totals.get(batch.getId()).compareTo(batch.getCurrentQuantity()) > 0) {
                throw new BusinessException(409, "领料数量超过批次可用量");
            }
            nativeRefs.add(new NativeRef(ref, nativeQuantity, batch.getQuantityUnit()));
            taskQuantity = taskQuantity.add(FgQuantityUnitConverter.convert(ref.getQuantity(), refUnit, portUnit, null));
        }
        // The picker speaks task units; cost and reservation must consume each
        // stock row's native units. Mutate only after every reference validates.
        nativeRefs.forEach(ref -> {
            ref.target().setQuantity(ref.quantity());
            ref.target().setUnit(ref.unit());
        });
        return taskQuantity;
    }

    private Map<String, InputUnit> taskMaterialTypes(String factoryId, WorkProcessTask task) {
        if (task.getWorkflowInstanceId() == null) return java.util.Collections.emptyMap();
        return taskPortRepository.findByFactoryIdAndWorkflowInstanceId(factoryId, task.getWorkflowInstanceId()).stream()
                .filter(port -> Objects.equals(task.getId(), port.getTaskId()))
                .filter(port -> port.getDirection() == WorkflowTaskPort.Direction.INPUT)
                .filter(port -> "RAW_MATERIAL".equalsIgnoreCase(port.getMaterialKind()))
                .filter(port -> port.getSkuId() != null && !port.getSkuId().isBlank())
                .collect(Collectors.toMap(WorkflowTaskPort::getSkuId, port -> {
                            String unit = port.getUnit();
                            if (unit == null || unit.isBlank() || "unitless".equalsIgnoreCase(unit.trim())) {
                                throw new BusinessException(409, "原料输入端口缺少有效单位快照，请核对当前工艺");
                            }
                            String code = YieldUnitResolver.canonicalPortUnit(port);
                            if (code == null || code.isBlank() || "unitless".equalsIgnoreCase(code)) {
                                throw new BusinessException(409, "原料输入端口缺少有效计量编码，请核对当前工艺");
                            }
                            return new InputUnit(code, java.util.Set.of(unit.trim()));
                        },
                        (left, right) -> {
                            if (!Objects.equals(left.code(), right.code())) {
                                throw new BusinessException(409, "同物料输入端口单位冲突，请核对当前工艺快照");
                            }
                            java.util.Set<String> displays = new java.util.HashSet<>(left.frozenDisplays());
                            displays.addAll(right.frozenDisplays());
                            return new InputUnit(left.code(), java.util.Set.copyOf(displays));
                        }, LinkedHashMap::new));
    }

    private void assertActorCanReport(WorkProcessTask task) {
        Long workerId = SecurityUtils.currentUserId();
        if (workerId == null) {
            throw new BusinessException(401, "无法确认当前操作员身份");
        }
        if (!Objects.equals(task.getFactoryId(), SecurityUtils.currentFactoryId())) {
            throw new BusinessException(403, "当前账号无权访问其他工厂的任务原料");
        }
        boolean supervisor = ReportAuthGuard.isSupervisor(ReportAuthGuard.currentRole());
        List<Long> assignees = task.getProductWorkProcessId() == null
                ? Collections.emptyList()
                : assigneeRepository.findByProductWorkProcessId(task.getProductWorkProcessId()).stream()
                    .map(a -> a.getWorkerId()).collect(Collectors.toList());
        ReportAuthGuard.assertCanReport(task.getAssignedTo(), assignees, workerId, supervisor);
    }

    private TaskMaterialBatchOptionDTO toOption(MaterialBatch batch, InputUnit input) {
        String nativeUnit = input.matches(batch.getQuantityUnit()) ? input.code()
                : UnitContractServiceImpl.crossLanguageCode(batch.getQuantityUnit());
        java.math.BigDecimal available = FgQuantityUnitConverter.convert(
                batch.getCurrentQuantity(), nativeUnit, input.code(), null);
        // Unknown package conversions are not offered as selectable material.
        if (available == null) return null;
        return TaskMaterialBatchOptionDTO.builder()
                .id(batch.getId())
                .batchNumber(batch.getBatchNumber())
                .materialTypeId(batch.getMaterialTypeId())
                .materialName(batch.getMaterialType() == null ? null : batch.getMaterialType().getName())
                .currentQuantity(available)
                .quantityUnit(input.code())
                .warehouseId(batch.getWarehouseId())
                .build();
    }
}
