package com.cretas.aims.service.config.aiconfig;

import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.ENTITY_TYPE;
import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.SETTING;
import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.requireInvocation;

@Service
@RequiredArgsConstructor
public class FactoryShortageConfigStore {
    private static final String MODULE = "FACTORY_CONFIG";
    private static final String ACTION = "AI_SHORTAGE_CONFIG_CHANGE";
    private static final String UNDO_OF = "undoOf";
    private static final String IMPACT = "开关影响后续开工与相关插单判断，已有业务记录保留。";

    private final FactorySettingsRepository settings;
    private final OperationLogRepository logs;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> previewFromGateway(Map<String, Object> context, JsonNode parameters) {
        var invocation = requireInvocation(context, parameters);
        String factoryId = invocation.principal().tenantId();
        Plan plan = plan(factoryId, invocation.request(), snapshot(settings.findByFactoryId(factoryId)));
        return Map.of("plan", plan, "currentValues", plan.current().asValues(),
                "canDo", plan.canDo(), "message", plan.message());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> executeFromGateway(Map<String, Object> context, JsonNode parameters) {
        var invocation = requireInvocation(context, parameters);
        var binding = invocation.binding();
        if (binding == null) {
            throw FactoryShortageConfigService.forbidden();
        }
        String factoryId = invocation.principal().tenantId();
        Optional<FactorySettings> row = settings.findByFactoryId(factoryId);
        Snapshot before = snapshot(row);
        Plan currentPlan = plan(factoryId, invocation.request(), before);
        if (!currentPlan.canDo() || !before.equals(binding.expected())
                || currentPlan.newValue() != binding.newValue()) {
            throw new BusinessException(409, "工厂设置已变化，请重新预览")
                    .withHint("请读取最新配置后重新预览，再确认所需变更");
        }
        FactorySettings existing = row.orElseThrow();
        existing.setBlockStartOnOpenShortage(binding.newValue());
        Snapshot after = snapshot(Optional.of(settings.saveAndFlush(existing)));
        if (after.version() != before.version() + 1 || after.enabled() != binding.newValue()) {
            throw new IllegalStateException("工厂设置保存结果与预期版本或值不符");
        }
        Map<String, Object> newValues = new LinkedHashMap<>(after.asValues());
        String undoOf = binding.request().undoOf();
        if (undoOf != null) {
            newValues.put(UNDO_OF, undoOf);
        }
        logs.saveAndFlush(OperationLog.builder()
                .factoryId(factoryId)
                .userId(invocation.userId())
                .module(MODULE)
                .action(ACTION)
                .entityType(ENTITY_TYPE)
                .entityId(binding.operationId())
                .oldValue(before.asValues())
                .newValue(newValues)
                .diff(List.of(Map.of("field", SETTING, "from", before.enabled(), "to", after.enabled())))
                .summary(binding.sanitizedUtterance())
                .build());
        return Map.of("operationId", binding.operationId());
    }

    /** Called after gateway execution, from a fresh transaction with no prior persistence context. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    Optional<Receipt> readReceipt(String factoryId, String operationId, long userId) {
        return findLog(factoryId, operationId, userId).map(log -> {
            Snapshot after = fromValues(log.getNewValue());
            Snapshot current = snapshot(settings.findByFactoryId(factoryId));
            if (Objects.equals(current.settingId(), after.settingId())
                    && (current.version() < after.version()
                    || (current.version().equals(after.version()) && current.enabled() != after.enabled()))) {
                throw new BusinessException(409, "配置回读与已提交审计不符，请核查操作编号")
                        .withHint("请凭操作编号核对当前配置与审计记录，核实前请勿重复提交")
                        .withHintTarget(operationId);
            }
            return new Receipt(operationId, fromValues(log.getOldValue()), after, current,
                    (String) log.getNewValue().get(UNDO_OF), log.getCreatedAt().toString());
        });
    }

    private Plan plan(String factoryId, FactoryShortageConfigService.Request request, Snapshot current) {
        if (current.settingId() == null) {
            return new Plan(current, Boolean.TRUE.equals(request.requestedValue()), false,
                    "当前默认关闭。请先到工厂设置初始化。", IMPACT);
        }
        boolean desired;
        if (request.undoOf() == null) {
            desired = request.requestedValue();
        } else {
            Optional<OperationLog> original = findLog(factoryId, request.undoOf(), null);
            if (original.isEmpty() || original.get().getNewValue().containsKey(UNDO_OF)
                    || !current.equals(fromValues(original.get().getNewValue()))) {
                return new Plan(current, current.enabled(), false,
                        "该操作不存在、无法撤销或设置版本已变化。", IMPACT);
            }
            desired = fromValues(original.get().getOldValue()).enabled();
        }
        boolean canDo = current.enabled() != desired;
        return new Plan(current, desired, canDo, canDo ? "等待确认" : "设置已是目标值。", IMPACT);
    }

    private Optional<OperationLog> findLog(String factoryId, String operationId, Long userId) {
        return logs.findOne(Example.of(OperationLog.builder()
                .factoryId(factoryId)
                .userId(userId)
                .module(MODULE)
                .action(ACTION)
                .entityType(ENTITY_TYPE)
                .entityId(operationId)
                .build()));
    }

    private static Snapshot snapshot(Optional<FactorySettings> row) {
        return row.map(settings -> new Snapshot(settings.getId(), settings.getVersion(),
                        Boolean.TRUE.equals(settings.getBlockStartOnOpenShortage())))
                .orElseGet(() -> new Snapshot(null, null, false));
    }

    private static Snapshot fromValues(Map<String, Object> values) {
        return new Snapshot(((Number) values.get("settingId")).intValue(),
                ((Number) values.get("version")).longValue(), (Boolean) values.get(SETTING));
    }

    public record Snapshot(Integer settingId, Long version, boolean enabled) {
        Map<String, Object> asValues() {
            if (settingId == null) {
                return Map.of(SETTING, enabled);
            }
            return Map.of("settingId", settingId, "version", version, SETTING, enabled);
        }
    }

    public record Plan(Snapshot current, boolean newValue, boolean canDo, String message, String impact) {}

    public record Receipt(String operationId, Snapshot before, Snapshot applied, Snapshot current,
                          String undoOf, String committedAt) {}
}
