package com.cretas.aims.service.skill.contract;

import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.skill.SkillExecutor;
import com.cretas.aims.service.skill.SkillRouterService;
import com.cretas.aims.service.skill.contract.SkillParameterContract.ParamSpec;
import com.cretas.aims.service.skill.contract.SkillParameterContract.ParamType;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * S6-02: save a successful Skill run as a reusable Skill of the factory, and run it again with new
 * parameters.
 *
 * <p>What a saved Skill can do is fixed by the run it came from: the same tools in the same order,
 * and only parameters that run actually had. Saving never widens anything: a contract parameter
 * the run did not have, a default outside the contract, or a captured value outside an edited
 * bound is refused. A rerun goes through {@link SkillExecutorImpl}'s contract mode, so its
 * parameters are validated before any tool runs and every tool goes through the tool gateway as
 * the user running it now; the saved Skill carries no authority of the user who saved it.</p>
 */
@Service
@RequiredArgsConstructor
public class SavedSkillService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final SkillRunStore store;
    private final SkillParameterContractValidator validator;
    private final SkillExecutor skillExecutor;
    private final ObjectMapper objectMapper;
    private final SkillRouterService skillRouter;

    public record SaveRequest(String runId, String name, String displayName, JsonNode parameterContract,
            Map<String, Object> defaults) {
    }

    public record RunResult(SkillResult result, String savedSkillId, String sourceRunId) {
    }

    /**
     * Runs a registered Skill by name as the given user: the same executor (and so the same gateway
     * routing and receipt) as the AI chat path. A successful run's receipt can then be saved.
     */
    public SkillResult runRegistered(String factoryId, Long userId, String userRole, String skillName,
            Map<String, Object> params, String userQuery, String sessionId) {
        if (skillName == null || skillName.isBlank()) {
            throw bad("skillName 必须填写");
        }
        Map<String, Object> callerParams = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        for (String key : callerParams.keySet()) {
            if (SkillParameterContractValidator.isReservedKey(key)) {
                throw bad("不接受身份参数：" + key);
            }
        }
        SkillContext context = SkillContext.builder()
                .factoryId(factoryId)
                .userId(String.valueOf(userId))
                .userRole(userRole)
                .sessionId(sessionId)
                .userQuery(userQuery == null || userQuery.isBlank() ? "skill:" + skillName : userQuery.trim())
                .extractedParams(callerParams)
                .build();
        SkillResult result = skillRouter.executeSkill(skillName, context);
        if (result != null && !result.isSuccess() && result.getMessage() != null
                && result.getMessage().startsWith("Skill not found")) {
            throw new BusinessException(404, "没有找到这个 Skill：" + skillName);
        }
        return result;
    }

    public List<SkillRunStore.Run> recentRuns(String factoryId, Long userId) {
        return store.listRuns(factoryId, userId, 20);
    }

    public SkillRunStore.Run run(String factoryId, String runId) {
        return store.findRun(factoryId, runId)
                .orElseThrow(() -> new BusinessException(404, "没有找到这次运行记录"));
    }

    public SkillRunStore.SavedSkill save(String factoryId, Long actorId, SaveRequest request) {
        if (request == null || request.runId() == null || request.name() == null) {
            throw bad("runId 和 name 都必须填写");
        }
        if (!NAME.matcher(request.name()).matches()) {
            throw bad("名称只能用小写字母、数字、下划线和短横线，最长 64 个字符");
        }
        SkillRunStore.Run source = run(factoryId, request.runId());
        if (!source.userId().equals(actorId)) {
            throw new BusinessException(403, "只能保存自己执行成功的运行");
        }
        if (!source.reusable()) {
            throw bad("只有全部步骤都经工具网关执行成功的运行才能保存为可复用 Skill");
        }
        Set<String> captured = new LinkedHashSet<>();
        source.steps().forEach(step -> step.params().fieldNames().forEachRemaining(captured::add));

        SkillParameterContract contract;
        try {
            contract = request.parameterContract() == null
                    ? inferContract(source)
                    : SkillParameterContract.parse(request.parameterContract());
        } catch (IllegalArgumentException malformed) {
            throw bad(malformed.getMessage());
        }
        for (ParamSpec spec : contract.params()) {
            if (!captured.contains(spec.name())) {
                throw bad("参数「" + spec.name() + "」不在原运行里，保存不能增加参数");
            }
        }

        Map<String, Object> defaults = request.defaults() == null ? Map.of() : request.defaults();
        if (!defaults.isEmpty()) {
            SkillParameterContractValidator.Validation checked = validator.validate(contract, factoryId, defaults);
            List<String> errors = checked.errors().stream().filter(error -> !error.startsWith("缺少必填参数")).toList();
            if (!errors.isEmpty()) {
                throw bad("默认值不符合契约：" + String.join("；", errors));
            }
        }

        List<SkillRunStore.SavedStep> steps = new ArrayList<>();
        for (SkillRunStore.Step step : source.steps()) {
            ObjectNode params = step.params().deepCopy();
            defaults.forEach((key, value) -> {
                if (params.has(key)) params.set(key, objectMapper.valueToTree(value));
            });
            Map<String, Object> asMap = objectMapper.convertValue(params, new TypeReference<>() { });
            for (Map.Entry<String, Object> entry : asMap.entrySet()) {
                contract.find(entry.getKey()).ifPresent(spec -> {
                    SkillParameterContractValidator.Validation one = validator.validate(
                            new SkillParameterContract(List.of(spec)), factoryId, Map.of(spec.name(), entry.getValue()));
                    if (!one.ok()) {
                        throw bad("原运行的值不符合编辑后的契约（" + step.tool() + "）：" + String.join("；", one.errors()));
                    }
                });
            }
            steps.add(new SkillRunStore.SavedStep(step.tool(), params));
        }

        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.name() : request.displayName().trim();
        if (displayName.length() > 200) {
            throw bad("显示名称最长 200 个字符");
        }
        try {
            return store.saveSkill(factoryId, request.name(), displayName, source, steps,
                    contract.toJson(objectMapper), actorId);
        } catch (DuplicateKeyException exists) {
            throw new BusinessException(409, "本厂已经有同名的 Skill：" + request.name());
        }
    }

    public List<SkillRunStore.SavedSkill> list(String factoryId) {
        return store.listSaved(factoryId);
    }

    public SkillRunStore.SavedSkill setEnabled(String factoryId, String savedSkillId, boolean enabled) {
        if (store.setSavedEnabled(factoryId, savedSkillId, enabled) != 1) {
            throw new BusinessException(404, "没有找到这个 Skill");
        }
        return store.findSaved(factoryId, savedSkillId).orElseThrow();
    }

    public RunResult runSaved(String factoryId, Long userId, String userRole, String savedSkillId,
            Map<String, Object> params, String sessionId) {
        SkillRunStore.SavedSkill saved = store.findSaved(factoryId, savedSkillId)
                .orElseThrow(() -> new BusinessException(404, "没有找到这个 Skill"));
        if (!saved.enabled()) {
            throw bad("这个 Skill 已停用");
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        List<String> tools = new ArrayList<>();
        for (SkillRunStore.SavedStep step : saved.steps()) {
            Map<String, Object> stepConfig = new LinkedHashMap<>();
            stepConfig.put("tool", step.tool());
            stepConfig.put("params", objectMapper.convertValue(step.params(), new TypeReference<Map<String, Object>>() { }));
            steps.add(stepConfig);
            tools.add(step.tool());
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(SkillParameterContract.CONFIG_KEY,
                objectMapper.convertValue(saved.parameterContract(), new TypeReference<Map<String, Object>>() { }));
        config.put("steps", steps);
        SkillDefinition definition = SkillDefinition.builder()
                .name(saved.name())
                .displayName(saved.displayName())
                .tools(tools)
                .config(config)
                .enabled(true)
                .source(SkillExecutorImpl.SAVED_SKILL_SOURCE_PREFIX + saved.id())
                .build();
        SkillContext context = SkillContext.builder()
                .factoryId(factoryId)
                .userId(String.valueOf(userId))
                .userRole(userRole)
                .sessionId(sessionId)
                .userQuery("saved-skill:" + saved.name())
                .extractedParams(params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params))
                .build();
        return new RunResult(skillExecutor.execute(definition, context), saved.id(), saved.sourceRunId());
    }

    /** Every scalar the run had becomes an optional parameter of its own type; objects stay fixed. */
    private SkillParameterContract inferContract(SkillRunStore.Run run) {
        Map<String, ParamSpec> specs = new LinkedHashMap<>();
        for (SkillRunStore.Step step : run.steps()) {
            step.params().fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                ParamType type = value.isBoolean() ? ParamType.BOOLEAN
                        : value.isIntegralNumber() ? ParamType.INTEGER
                        : value.isNumber() ? ParamType.NUMBER
                        : value.isTextual() && DATE.matcher(value.asText()).matches() ? ParamType.DATE
                        : value.isTextual() ? ParamType.STRING : null;
                if (type != null && !SkillParameterContractValidator.isReservedKey(field.getKey())) {
                    specs.putIfAbsent(field.getKey(), new ParamSpec(field.getKey(), type, false, null, null,
                            null, List.of(), null));
                }
            });
        }
        return new SkillParameterContract(List.copyOf(specs.values()));
    }

    private static BusinessException bad(String message) {
        return new BusinessException(400, message);
    }
}
