package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductWorkProcessAssignee;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Propagation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductWorkProcessServiceImpl implements ProductWorkProcessService {

    private static final Logger log = LoggerFactory.getLogger(ProductWorkProcessServiceImpl.class);
    private final ProductWorkProcessRepository repository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductWorkProcessAssigneeRepository assigneeRepository;
    private final UserRepository userRepository;
    private final com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;
    @PersistenceContext
    private EntityManager entityManager;
    private static final ObjectMapper BINDING_MAPPER = new ObjectMapper();

    @Override
    @Transactional
    public ProductWorkProcessDTO create(String factoryId, ProductWorkProcessDTO dto) {
        log.info("Creating product-work-process association for factory: {}", factoryId);
        // 工艺保密: 工序链就是工艺图, 给受保护产品加工序要编辑授权
        processSecretGuard.requireProduct(factoryId, dto.getProductTypeId(),
                com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT, "product-work-process create");

        if (repository.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                factoryId, dto.getProductTypeId(), dto.getWorkProcessId())) {
            throw new BusinessException(409, "该产品已关联此工序")
                    .withHint("请选择其他工序, 或刷新列表查看现有关联");
        }

        // Validate work process exists
        WorkProcess selectedProcess = workProcessRepository.findByFactoryIdAndId(factoryId, dto.getWorkProcessId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkProcess", "id", dto.getWorkProcessId()));
        if (!selectedProcess.isSelectableForNew()) {
            throw new BusinessException(409, "该工序已停用或已合并，不能用于新的 SKU 关联")
                    .withHint("已有 SKU、Workflow 和生产任务不受影响；请选择治理后的主工序")
                    .withHintTarget("workProcessId");
        }

        // T121: if assigneeWorkerIds provided, use assignees[0] as primary; else fall back to responsibleWorkerId field.
        List<Long> assignees = dto.getAssigneeWorkerIds();
        boolean hasAssignees = assignees != null && !assignees.isEmpty();

        // Normalize -1L sentinel to null so it never lands in DB via create
        Long rw = dto.getResponsibleWorkerId();
        Long normalizedRw;
        if (hasAssignees) {
            // multi-assignee path: primary = assignees[0]
            normalizedRw = assignees.get(0);
        } else if (rw != null && rw == -1L) {
            normalizedRw = null;
        } else {
            normalizedRw = rw;
        }

        ProductWorkProcess entity = ProductWorkProcess.builder()
                .factoryId(factoryId)
                .productTypeId(dto.getProductTypeId())
                .workProcessId(dto.getWorkProcessId())
                .processOrder(dto.getProcessOrder() != null ? dto.getProcessOrder() : 0)
                .unitOverride(dto.getUnitOverride())
                .estimatedMinutesOverride(dto.getEstimatedMinutesOverride())
                .responsibleWorkerId(normalizedRw)
                // Wave2 可配置报工粒度: null → 默认 true (逐道报, 向后兼容); 显式 false → 免报。
                .reportingRequired(dto.getReportingRequired() == null ? Boolean.TRUE : dto.getReportingRequired())
                // 半成品注入工序 (张权 R4): null → 默认 false (普通工序); 显式 true → 逐道录入可选 SFI/FG。
                .allowSemiFinishedInjection(
                        dto.getAllowSemiFinishedInjection() == null ? Boolean.FALSE : dto.getAllowSemiFinishedInjection())
                // 多上游混批: null → 默认 false (单批); 显式 true → 报工可添加多个来源批。
                .allowMultipleUpstreamSources(
                        dto.getAllowMultipleUpstreamSources() == null ? Boolean.FALSE : dto.getAllowMultipleUpstreamSources())
                // 成品库存作来源: null → 默认 false; 显式 true → 报工来源可选择成品库存。
                .allowFinishedGoodsSource(
                        dto.getAllowFinishedGoodsSource() == null ? Boolean.FALSE : dto.getAllowFinishedGoodsSource())
                // 工序成本配置 (报工自动继承, 防呆: 操作员不手填会计类别)
                .defaultCostCategory(dto.getDefaultCostCategory())
                .packagingTemplate(dto.getPackagingTemplate())
                .auxAllocMethod(dto.getAuxAllocMethod())
                // 段2(B) 辅料对账配置 (标准率/辅料单价/基准)
                .standardYieldRate(dto.getStandardYieldRate())
                .auxUnitPrice(dto.getAuxUnitPrice())
                .auxBasis(dto.getAuxBasis())
                .build();

        ProductWorkProcess saved = repository.save(entity);

        // T121: upsert join table
        upsertAssignees(saved.getId(), hasAssignees ? assignees : toSingletonList(normalizedRw));

        return toDTO(saved, null, loadAssigneeIds(saved.getId()));
    }

    @Override
    public List<ProductWorkProcessDTO> listByProduct(String factoryId, String productTypeId) {
        log.debug("Listing work processes for product {} in factory {}", productTypeId, factoryId);
        List<ProductWorkProcess> associations = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId);

        // Pre-load work processes for enrichment
        List<String> wpIds = associations.stream()
                .map(ProductWorkProcess::getWorkProcessId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, WorkProcess> wpMap = workProcessRepository.findByFactoryIdAndIdIn(factoryId, wpIds)
                .stream()
                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));
        String productUnit = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(product -> product.getUnit())
                .orElse(null);

        // T121: pre-load all assignees for these product_work_process ids in one query
        List<Long> pwpIds = associations.stream().map(ProductWorkProcess::getId).collect(Collectors.toList());
        Map<Long, List<Long>> assigneeMap = loadAssigneeIdsForAll(pwpIds);

        // T135: batch-load user names for responsible workers to display on chips
        Set<Long> workerIds = associations.stream()
                .map(ProductWorkProcess::getResponsibleWorkerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, String> workerNameMap = workerIds.isEmpty() ? Map.of() :
                userRepository.findByIdIn(workerIds).stream()
                        .collect(Collectors.toMap(
                                User::getId,
                                u -> u.getFullName() != null && !u.getFullName().isBlank()
                                        ? u.getFullName() : u.getUsername()
                        ));

        return associations.stream()
                .map(a -> {
                    ProductWorkProcessDTO dto = toDTO(a, wpMap.get(a.getWorkProcessId()),
                            assigneeMap.getOrDefault(a.getId(), List.of()), productUnit);
                    if (a.getResponsibleWorkerId() != null) {
                        dto.setResponsibleWorkerName(workerNameMap.get(a.getResponsibleWorkerId()));
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProductWorkProcessDTO update(String factoryId, Long id, ProductWorkProcessDTO dto) {
        log.info("Updating product-work-process {} for factory: {}", id, factoryId);
        ProductWorkProcess entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductWorkProcess", "id", id.toString()));
        // 工艺保密: 改标准出成率 / 工序顺序是改工艺; 只改人员指派等不算 (班组长照常排人)
        if (dto.getStandardYieldRate() != null || dto.getProcessOrder() != null) {
            processSecretGuard.requireProduct(factoryId, entity.getProductTypeId(),
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT, "product-work-process update " + id);
        }

        if (dto.getProcessOrder() != null) entity.setProcessOrder(dto.getProcessOrder());
        if (dto.getUnitOverride() != null) entity.setUnitOverride(dto.getUnitOverride());
        if (dto.getEstimatedMinutesOverride() != null) entity.setEstimatedMinutesOverride(dto.getEstimatedMinutesOverride());
        // Wave2 可配置报工粒度: null → no-change (partial update 不动现有值); 非 null → set (true/false 均可显式设)。
        if (dto.getReportingRequired() != null) entity.setReportingRequired(dto.getReportingRequired());
        // 半成品注入工序 partial update: null → no-change; 非 null → set (true/false 均可显式设)。
        if (dto.getAllowSemiFinishedInjection() != null) entity.setAllowSemiFinishedInjection(dto.getAllowSemiFinishedInjection());
        // 多上游混批 partial update: null → no-change; 非 null → set (true/false 均可显式设)。
        if (dto.getAllowMultipleUpstreamSources() != null) entity.setAllowMultipleUpstreamSources(dto.getAllowMultipleUpstreamSources());
        // 成品库存作来源 partial update: null → no-change; 非 null → set。
        if (dto.getAllowFinishedGoodsSource() != null) entity.setAllowFinishedGoodsSource(dto.getAllowFinishedGoodsSource());
        // 工序成本配置 partial update: null → no-change; 非 null → set
        if (dto.getDefaultCostCategory() != null) entity.setDefaultCostCategory(dto.getDefaultCostCategory());
        if (dto.getPackagingTemplate() != null) entity.setPackagingTemplate(dto.getPackagingTemplate());
        if (dto.getAuxAllocMethod() != null) entity.setAuxAllocMethod(dto.getAuxAllocMethod());
        // 段2(B) 辅料对账配置 partial update: null → no-change; 非 null → set
        if (dto.getStandardYieldRate() != null) entity.setStandardYieldRate(dto.getStandardYieldRate());
        if (dto.getAuxUnitPrice() != null) entity.setAuxUnitPrice(dto.getAuxUnitPrice());
        if (dto.getAuxBasis() != null) entity.setAuxBasis(dto.getAuxBasis());

        // T121: if assigneeWorkerIds provided, they govern responsible_worker_id + join table.
        List<Long> assignees = dto.getAssigneeWorkerIds();
        boolean hasAssignees = assignees != null && !assignees.isEmpty();

        if (hasAssignees) {
            // multi-assignee update: primary = assignees[0], update join table
            entity.setResponsibleWorkerId(assignees.get(0));
        } else {
            // Single-value path (backward-compat):
            // Three-state responsibleWorkerId:
            //   null        → no change (partial update — leave existing value alone)
            //   -1L         → clear (set null; sentinel must never persist as a real user id)
            //   positive id → set
            Long rw = dto.getResponsibleWorkerId();
            if (rw != null) {
                entity.setResponsibleWorkerId(rw == -1L ? null : rw);
            }
        }

        ProductWorkProcess saved = repository.save(entity);

        // T121: upsert join table when assignees explicitly provided
        if (hasAssignees) {
            upsertAssignees(saved.getId(), assignees);
        } else if (dto.getResponsibleWorkerId() != null) {
            // Single-value changed → sync join table to reflect new primary
            Long effectiveRw = dto.getResponsibleWorkerId() == -1L ? null : dto.getResponsibleWorkerId();
            upsertAssignees(saved.getId(), toSingletonList(effectiveRw));
        }

        return toDTO(saved, null, loadAssigneeIds(saved.getId()));
    }

    @Override
    @Transactional
    public void delete(String factoryId, Long id) {
        log.info("Deleting product-work-process {} for factory: {}", id, factoryId);
        ProductWorkProcess entity = repository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductWorkProcess", "id", id.toString()));
        processSecretGuard.requireProduct(factoryId, entity.getProductTypeId(),
                com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT, "product-work-process delete " + id);
        repository.delete(entity);
    }

    @Override
    @Transactional
    public void batchSort(String factoryId, List<ProductWorkProcessDTO.SortItem> items) {
        log.info("Batch sorting {} product-work-process items for factory: {}", items.size(), factoryId);
        for (ProductWorkProcessDTO.SortItem item : items) {
            repository.findByFactoryIdAndId(factoryId, item.getId())
                    .ifPresent(entity -> {
                        processSecretGuard.requireProduct(factoryId, entity.getProductTypeId(),
                                com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                                "product-work-process batch-sort " + entity.getId());
                        entity.setProcessOrder(item.getProcessOrder());
                        repository.save(entity);
                    });
        }
    }

    @Override
    @Transactional
    public int copyChain(String factoryId, String sourceProductId, String targetProductId) {
        if (sourceProductId == null || targetProductId == null || sourceProductId.equals(targetProductId)) {
            throw new BusinessException(400, "源产品与目标产品必须是两个不同的产品");
        }
        // 工艺保密: 工序链 (含标准出成率) 复制到没有保密策略的产品上 = 搬出保护范围
        processSecretGuard.requireCopySource(factoryId, sourceProductId,
                "copy-chain " + sourceProductId + " -> " + targetProductId);
        processSecretGuard.requireProduct(factoryId, targetProductId,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                "copy-chain target " + targetProductId);
        List<ProductWorkProcess> source = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, sourceProductId);
        if (source.isEmpty()) {
            throw new BusinessException(400, "源产品没有工序链可复制, 请先给源产品配置工序");
        }
        List<String> sourceProcessIds = source.stream()
                .map(ProductWorkProcess::getWorkProcessId)
                .distinct()
                .toList();
        boolean hasGovernedProcess = workProcessRepository
                .findByFactoryIdAndIdIn(factoryId, sourceProcessIds)
                .stream()
                .anyMatch(process -> !process.isSelectableForNew());
        if (hasGovernedProcess) {
            throw new BusinessException(409, "源产品包含已停用或已合并工序，不能复制为新的工序链")
                    .withHint("源产品和既有生产仍可继续使用；新配置请选择当前可用的主工序");
        }
        List<ProductWorkProcess> existing = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, targetProductId);
        if (!existing.isEmpty()) {
            throw new BusinessException(409, "目标产品已有 " + existing.size() + " 道工序, 复制前请先清空目标工序链")
                    .withHint("如需覆盖, 请先删除目标产品现有工序再复制");
        }
        int copied = 0;
        for (ProductWorkProcess src : source) {
            ProductWorkProcess entity = ProductWorkProcess.builder()
                    .factoryId(factoryId)
                    .productTypeId(targetProductId)
                    .workProcessId(src.getWorkProcessId())
                    .processOrder(src.getProcessOrder())
                    .unitOverride(src.getUnitOverride())
                    .estimatedMinutesOverride(src.getEstimatedMinutesOverride())
                    // 防呆: 不复制人员指派 (各产品班组不同) — responsibleWorkerId/assignees 留空, 由用户另配
                    .responsibleWorkerId(null)
                    .reportingRequired(src.getReportingRequired())
                    .allowSemiFinishedInjection(src.getAllowSemiFinishedInjection())
                    .allowMultipleUpstreamSources(src.getAllowMultipleUpstreamSources())
                    .allowFinishedGoodsSource(src.getAllowFinishedGoodsSource())
                    .defaultCostCategory(src.getDefaultCostCategory())
                    .packagingTemplate(src.getPackagingTemplate())
                    .auxAllocMethod(src.getAuxAllocMethod())
                    .standardYieldRate(src.getStandardYieldRate())
                    .auxUnitPrice(src.getAuxUnitPrice())
                    .auxBasis(src.getAuxBasis())
                    .build();
            repository.save(entity);
            copied++;
        }
        log.info("[COPY-CHAIN] factory={} {} -> {} copied {} processes (assignees not copied)",
                factoryId, sourceProductId, targetProductId, copied);
        return copied;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductWorkProcessService.PreparedChain prepareChain(String factoryId, String sourceProductId) {
        if (sourceProductId == null || sourceProductId.isBlank()) {
            throw new BusinessException(400, "继承来源产品无效");
        }
        if (entityManager != null) {
            entityManager.clear();
        }
        productTypeRepository.findByIdAndFactoryId(sourceProductId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductType", "id", sourceProductId));
        List<ProductWorkProcess> source = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, sourceProductId);
        List<String> processIds = source.stream()
                .map(ProductWorkProcess::getWorkProcessId)
                .distinct()
                .toList();
        List<WorkProcess> masterRows = processIds.isEmpty() ? List.of()
                : entityManager == null
                ? workProcessRepository.findByFactoryIdAndIdIn(factoryId, processIds)
                : processIds.stream()
                        .map(id -> entityManager.find(WorkProcess.class, id))
                        .filter(Objects::nonNull)
                        .toList();
        Map<String, WorkProcess> masters = masterRows
                .stream()
                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));
        List<ProductWorkProcessService.PreparedProcess> processes = source.stream()
                .map(this::toPreparedProcess)
                .toList();
        List<ProductWorkProcessService.PreparedMaster> masterPins = processIds.stream()
                .map(id -> {
                    WorkProcess master = masters.get(id);
                    if (master == null || !Objects.equals(factoryId, master.getFactoryId())
                            || master.getDeletedAt() != null || !master.isSelectableForNew()) {
                        throw new BusinessException(409, "源产品包含已停用或已合并工序，不能确认继承链")
                                .withHint("请重新预览并选择当前可用的主工序");
                    }
                    return new ProductWorkProcessService.PreparedMaster(
                            id, master.getLockVersion(), master.isSelectableForNew());
                })
                .toList();
        List<String> processNames = source.stream()
                .map(row -> {
                    WorkProcess master = masters.get(row.getWorkProcessId());
                    return master == null || master.getProcessName() == null
                            ? row.getWorkProcessId() : master.getProcessName();
                })
                .toList();
        return new ProductWorkProcessService.PreparedChain(
                sourceProductId, processes, masterPins, digest(processes), processNames);
    }

    @Override
    @Transactional(readOnly = true)
    public void validatePreparedChain(String factoryId, String sourceProductId,
                                      ProductWorkProcessService.PreparedChain preparedChain) {
        if (preparedChain == null || !Objects.equals(sourceProductId, preparedChain.sourceProductId())) {
            throw new BusinessException(409, "继承链确认凭证无效，请重新预览").withHint("请重新发起产品创建预览");
        }
        if (preparedChain.digest() == null
                || !Objects.equals(digest(preparedChain.processes()), preparedChain.digest())) {
            throw new BusinessException(409, "继承链确认凭证内容已损坏，请重新预览")
                    .withHint("请重新发起产品创建预览");
        }
        ProductWorkProcessService.PreparedChain current = prepareChain(factoryId, sourceProductId);
        if (!Objects.equals(current.digest(), preparedChain.digest())
                || current.processes().size() != preparedChain.processes().size()
                || !current.masters().equals(preparedChain.masters())
                || !current.processNames().equals(preparedChain.processNames())) {
            throw new BusinessException(409, "源产品工序链或主工序版本已变化，请重新预览")
                    .withHint("未创建产品；请刷新源产品后重新发起预览");
        }
    }

    @Override
    @Transactional
    public int copyPreparedChain(String factoryId, String sourceProductId, String targetProductId,
                                 ProductWorkProcessService.PreparedChain preparedChain) {
        if (sourceProductId == null || targetProductId == null || sourceProductId.equals(targetProductId)) {
            throw new BusinessException(400, "源产品与目标产品必须是两个不同的产品");
        }
        processSecretGuard.requireCopySource(factoryId, sourceProductId,
                "copy-chain-bound " + sourceProductId + " -> " + targetProductId);
        processSecretGuard.requireProduct(factoryId, targetProductId,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                "copy-chain-bound target " + targetProductId);
        validatePreparedChain(factoryId, sourceProductId, preparedChain);
        if (preparedChain.processes().isEmpty()) {
            return 0;
        }
        lockPreparedMastersForWrite(factoryId, preparedChain);
        List<ProductWorkProcess> existing = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, targetProductId);
        if (!existing.isEmpty()) {
            throw new BusinessException(409, "目标产品已有工序, 复制前请先清空目标工序链")
                    .withHint("如需覆盖, 请先删除目标产品现有工序再复制");
        }
        for (ProductWorkProcessService.PreparedProcess src : preparedChain.processes()) {
            repository.save(ProductWorkProcess.builder()
                    .factoryId(factoryId)
                    .productTypeId(targetProductId)
                    .workProcessId(src.workProcessId())
                    .processOrder(src.processOrder())
                    .unitOverride(src.unitOverride())
                    .estimatedMinutesOverride(src.estimatedMinutesOverride())
                    .responsibleWorkerId(null)
                    .reportingRequired(src.reportingRequired())
                    .allowSemiFinishedInjection(src.allowSemiFinishedInjection())
                    .allowMultipleUpstreamSources(src.allowMultipleUpstreamSources())
                    .allowFinishedGoodsSource(src.allowFinishedGoodsSource())
                    .defaultCostCategory(src.defaultCostCategory())
                    .packagingTemplate(src.packagingTemplate())
                    .auxAllocMethod(src.auxAllocMethod())
                    .standardYieldRate(src.standardYieldRate())
                    .auxUnitPrice(src.auxUnitPrice())
                    .auxBasis(src.auxBasis())
                    .build());
        }
        repository.flush();
        return preparedChain.processes().size();
    }

    private void lockPreparedMastersForWrite(
            String factoryId, ProductWorkProcessService.PreparedChain preparedChain) {
        if (entityManager == null || preparedChain.masters().isEmpty()) {
            return;
        }
        entityManager.clear();
        List<String> ids = preparedChain.masters().stream()
                .map(ProductWorkProcessService.PreparedMaster::id)
                .sorted()
                .toList();
        Map<String, WorkProcess> locked = workProcessRepository
                .lockByFactoryIdAndIdIn(factoryId, ids)
                .stream()
                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));
        if (locked.size() != preparedChain.masters().size()
                || preparedChain.masters().stream().anyMatch(pin -> {
                    WorkProcess master = locked.get(pin.id());
                    return master == null || !Objects.equals(factoryId, master.getFactoryId())
                            || master.getDeletedAt() != null || !master.isSelectableForNew()
                            || !Objects.equals(pin.lockVersion(), master.getLockVersion());
                })) {
            throw new BusinessException(409, "源产品工序链或主工序版本已变化，请重新预览")
                    .withHint("未创建产品；请刷新源产品后重新发起预览");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void verifyPreparedChainReadback(String factoryId, String targetProductId,
                                            ProductWorkProcessService.PreparedChain preparedChain) {
        if (entityManager != null) {
            entityManager.clear();
        }
        List<ProductWorkProcess> readback = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, targetProductId);
        List<ProductWorkProcessService.PreparedProcess> actual = readback.stream()
                .map(this::toPreparedProcess)
                .toList();
        if (preparedChain == null || actual.size() != preparedChain.processes().size()
                || !Objects.equals(digest(actual), preparedChain.digest())) {
            throw new BusinessException(502, "产品已创建但工序链复制结果无法核对，请人工检查")
                    .withCode("PRODUCT_CHAIN_READBACK_UNKNOWN")
                    .withHint("系统不会自动重试；请根据产品ID核对工序链");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void verifyEmptyChainReadback(String factoryId, String targetProductId) {
        if (entityManager != null) {
            entityManager.clear();
        }
        List<ProductWorkProcess> readback = repository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, targetProductId);
        if (!readback.isEmpty()) {
            throw new BusinessException(502, "产品已创建但空工序链核对失败，请人工检查")
                    .withCode("PRODUCT_CHAIN_READBACK_UNKNOWN")
                    .withHint("系统不会自动重试；请根据产品ID核对工序链");
        }
    }

    private ProductWorkProcessService.PreparedProcess toPreparedProcess(ProductWorkProcess row) {
        return new ProductWorkProcessService.PreparedProcess(
                row.getWorkProcessId(), row.getProcessOrder(), row.getUnitOverride(),
                row.getEstimatedMinutesOverride(), row.getReportingRequired(),
                row.getAllowSemiFinishedInjection(), row.getAllowMultipleUpstreamSources(),
                row.getAllowFinishedGoodsSource(), row.getDefaultCostCategory(),
                row.getPackagingTemplate(), row.getAuxAllocMethod(), row.getStandardYieldRate(),
                row.getAuxUnitPrice(), row.getAuxBasis());
    }

    private String digest(List<ProductWorkProcessService.PreparedProcess> processes) {
        List<Map<String, Object>> rows = processes.stream().map(row -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("workProcessId", row.workProcessId());
            values.put("processOrder", row.processOrder());
            values.put("unitOverride", row.unitOverride());
            values.put("estimatedMinutesOverride", row.estimatedMinutesOverride());
            values.put("reportingRequired", row.reportingRequired());
            values.put("allowSemiFinishedInjection", row.allowSemiFinishedInjection());
            values.put("allowMultipleUpstreamSources", row.allowMultipleUpstreamSources());
            values.put("allowFinishedGoodsSource", row.allowFinishedGoodsSource());
            values.put("defaultCostCategory", row.defaultCostCategory());
            values.put("packagingTemplate", row.packagingTemplate());
            values.put("auxAllocMethod", row.auxAllocMethod());
            values.put("standardYieldRate", row.standardYieldRate());
            values.put("auxUnitPrice", row.auxUnitPrice());
            values.put("auxBasis", row.auxBasis());
            return values;
        }).toList();
        JsonNode normalized = normalizeNumbers(BINDING_MAPPER.valueToTree(rows));
        return ToolCommandDigest.parametersHash(
                BINDING_MAPPER.valueToTree(Map.of("processes", normalized)));
    }

    private JsonNode normalizeNumbers(JsonNode value) {
        if (value == null || value.isNull()) return value;
        if (value.isFloatingPointNumber() || value.isBigDecimal()) {
            return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        }
        if (value.isArray()) {
            ArrayNode array = BINDING_MAPPER.createArrayNode();
            value.forEach(item -> array.add(normalizeNumbers(item)));
            return array;
        }
        if (value.isObject()) {
            ObjectNode object = BINDING_MAPPER.createObjectNode();
            value.fields().forEachRemaining(entry ->
                    object.set(entry.getKey(), normalizeNumbers(entry.getValue())));
            return object;
        }
        return value;
    }

    /** Legacy overload — called from batchSort / delete (no assignee enrichment needed). */
    private ProductWorkProcessDTO toDTO(ProductWorkProcess entity, WorkProcess wp) {
        return toDTO(entity, wp, List.of(), null);
    }

    private ProductWorkProcessDTO toDTO(ProductWorkProcess entity, WorkProcess wp, List<Long> assigneeIds) {
        return toDTO(entity, wp, assigneeIds, null);
    }

    private ProductWorkProcessDTO toDTO(
            ProductWorkProcess entity,
            WorkProcess wp,
            List<Long> assigneeIds,
            String productUnit) {
        ProductWorkProcessDTO.ProductWorkProcessDTOBuilder builder = ProductWorkProcessDTO.builder()
                .id(entity.getId())
                .productTypeId(entity.getProductTypeId())
                .workProcessId(entity.getWorkProcessId())
                .processOrder(entity.getProcessOrder())
                .unitOverride(entity.getUnitOverride())
                .estimatedMinutesOverride(entity.getEstimatedMinutesOverride())
                .responsibleWorkerId(entity.getResponsibleWorkerId())
                .assigneeWorkerIds(assigneeIds.isEmpty() ? null : assigneeIds)
                .isActive(entity.getIsActive())
                .reportingRequired(entity.getReportingRequired())
                .allowSemiFinishedInjection(entity.getAllowSemiFinishedInjection())
                .allowMultipleUpstreamSources(entity.getAllowMultipleUpstreamSources())
                .allowFinishedGoodsSource(entity.getAllowFinishedGoodsSource())
                .defaultCostCategory(entity.getDefaultCostCategory())
                .packagingTemplate(entity.getPackagingTemplate())
                .auxAllocMethod(entity.getAuxAllocMethod())
                .standardYieldRate(entity.getStandardYieldRate())
                .auxUnitPrice(entity.getAuxUnitPrice())
                .auxBasis(entity.getAuxBasis())
                .createdAt(entity.getCreatedAt());

        if (wp != null) {
            builder.processName(wp.getProcessName())
                    .processCategory(wp.getProcessCategory())
                    .defaultUnit(entity.getUnitOverride() != null ? entity.getUnitOverride() : productUnit)
                    .defaultOutputUnit(productUnit)
                    .defaultEstimatedMinutes(wp.getEstimatedMinutes())
                    .customFieldSchema(wp.getCustomFieldSchema());
        }

        return builder.build();
    }

    // ── T121 helpers ────────────────────────────────────────────────────────

    /**
     * Upsert join table: soft-delete rows not in {@code desiredWorkerIds}; add missing ones.
     * If {@code desiredWorkerIds} is empty (cleared), soft-delete all existing rows.
     */
    @Transactional
    protected void upsertAssignees(Long pwpId, List<Long> desiredWorkerIds) {
        List<Long> desired = desiredWorkerIds == null ? List.of() : desiredWorkerIds;
        Set<Long> desiredSet = new java.util.HashSet<>(desired);

        // Soft-delete rows not in desired list
        List<ProductWorkProcessAssignee> existing = assigneeRepository.findByProductWorkProcessId(pwpId);
        for (ProductWorkProcessAssignee row : existing) {
            if (!desiredSet.contains(row.getWorkerId())) {
                row.softDelete();
                assigneeRepository.save(row);
            }
        }

        // Add missing rows
        Set<Long> existingWorkerIds = existing.stream()
                .map(ProductWorkProcessAssignee::getWorkerId)
                .collect(Collectors.toSet());
        for (Long workerId : desired) {
            if (!existingWorkerIds.contains(workerId)) {
                assigneeRepository.save(ProductWorkProcessAssignee.builder()
                        .productWorkProcessId(pwpId)
                        .workerId(workerId)
                        .build());
            }
        }
    }

    /** Load active assignee worker_ids for a single PWP row. */
    private List<Long> loadAssigneeIds(Long pwpId) {
        return assigneeRepository.findByProductWorkProcessId(pwpId).stream()
                .map(ProductWorkProcessAssignee::getWorkerId)
                .collect(Collectors.toList());
    }

    /**
     * Batch-load assignee worker_ids for multiple PWP ids (avoids N+1).
     * Returns map: pwpId → list of worker_ids.
     */
    private Map<Long, List<Long>> loadAssigneeIdsForAll(List<Long> pwpIds) {
        if (pwpIds.isEmpty()) return Map.of();
        // Fetch all assignees for the given pwp ids in one query via Spring Data findAll + filter.
        // For small lists this is fine; could be a @Query for very large lists.
        Map<Long, List<Long>> result = new java.util.HashMap<>();
        for (Long pwpId : pwpIds) {
            List<Long> ids = assigneeRepository.findByProductWorkProcessId(pwpId).stream()
                    .map(ProductWorkProcessAssignee::getWorkerId)
                    .collect(Collectors.toList());
            if (!ids.isEmpty()) result.put(pwpId, ids);
        }
        return result;
    }

    /** Helper: wrap a nullable Long into a singleton list (empty if null). */
    private static List<Long> toSingletonList(Long v) {
        return v == null ? List.of() : List.of(v);
    }
}
