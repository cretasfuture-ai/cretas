package com.cretas.aims.service.module;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.config.ModuleGateStatus;
import com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块开关的<b>唯一客户面入口</b> —— 一次操作说清楚落到哪一层。
 *
 * <h2>为什么要收敛入口</h2>
 *
 * 2026-08-14 实测,能写这两张表的地方散在 5 个类里, 而且各写各的:
 *
 * <pre>
 *                                              前端可见性  后端门禁
 *   FactoryBuildApplyService (对话建厂)            ✅         ✅   (create-only)
 *   FeatureConfigController  (web-admin 配置页)    ⚠️ 只写 config JSON, 从不碰 enabled
 *   FactoryConfigServiceImpl (← ConfigController)  ❌         ✅
 *   OnboardingModuleConfigProvisioner              ❌         ✅   (apply() 生产零调用)
 *   FactoryRoleModuleOverrideController            ❌         ✅
 * </pre>
 *
 * 更糟的是 web-admin「功能模块配置」页的开关打的是
 * {@code PUT /feature-config/{moduleId}/toggle} —— <b>后端根本没有这条路由</b>(404),
 * 而存在的那个 {@code PUT /{moduleId}} 只写 {@code config} JSON。
 * ⇒ 客户在那个页面上<b>两层都关不掉</b>。
 *
 * <h2>词汇表:只用客户看得见的那一套</h2>
 *
 * 后端门禁码有 19 个({@code finance_ap} / {@code finance_ar} / ...), 客户看不懂也不该看懂。
 * 本服务对外只收 {@link InterviewModuleConfigCatalog} 的 {@code moduleId}
 * (财务 / 生产 / 质检 ... 13 个), 由目录 1:N 展开成门禁码 —— 这条映射已被
 * {@code check-module-gate-binding.py} 钉着(386/386)。
 *
 * ⛔ 不做反向映射(门禁码 → moduleId): 那是 N:1 且有歧义 ——
 * 单独关掉 {@code finance_ap} 不该把整个「财务」界面藏掉。低层按门禁码操作的
 * {@code PATCH /config/modules/{moduleCode}/toggle} 保留为工程用途, 不是客户入口。
 *
 * <h2>⚠️ 后端层写了不一定生效</h2>
 *
 * 见 {@link ModuleSwitchState} 的类注释:门禁要生效需要三样齐全, 其中
 * 「该厂有 PUBLISHED 的 factory_configurations」本地实测<b>全库 0 行</b>。
 * 本服务如实写库, 但<b>不假装</b>写了就一定 403。
 *
 * @since 2026-08-14
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
@RequiredArgsConstructor
public class ModuleSwitchService {

    private final FactoryConfigService factoryConfigService;
    private final FactoryFeatureConfigRepository featureConfigRepository;

    /**
     * 切换一个模块的开关。
     *
     * @param moduleId 客户面模块 id(见 {@link InterviewModuleConfigCatalog}), 不是后端门禁码
     * @param layers   落到哪一层 —— 默认语义是 {@link ModuleSwitchLayers#BOTH};
     *                 灰度用 {@link ModuleSwitchLayers#FRONTEND_ONLY}
     * @return 操作后的状态
     */
    @Transactional
    public ModuleSwitchResult switchModule(String factoryId, String moduleId, boolean enabled,
                                           ModuleSwitchLayers layers, Long operatorId) {
        return switchModule(factoryId, moduleId, enabled, layers,
                gateCode -> factoryConfigService.toggleModule(factoryId, gateCode, enabled, operatorId));
    }

    /**
     * Typed module switch entry point; platform actors never enter factory-user columns.
     */
    @Transactional
    public ModuleSwitchResult switchModuleByActor(String factoryId, String moduleId, boolean enabled,
                                                  ModuleSwitchLayers layers, ActorRef actor) {
        if (actor == null) throw new IllegalArgumentException("actor is required");
        return switchModule(factoryId, moduleId, enabled, layers,
                gateCode -> factoryConfigService.toggleModuleByActor(factoryId, gateCode, enabled, actor));
    }

    @FunctionalInterface
    private interface GateToggle {
        void toggle(String gateCode);
    }

    private ModuleSwitchResult switchModule(String factoryId, String moduleId, boolean enabled,
                                            ModuleSwitchLayers layers, GateToggle gateToggle) {
        InterviewModuleConfigCatalog.Entry entry = InterviewModuleConfigCatalog.find(moduleId);
        if (entry == null) {
            throw new BusinessException(400, "未知模块: " + moduleId)
                    .withHint("可用模块见 GET /config/module-switch-state")
                    .withHintTarget("moduleId");
        }
        ModuleSwitchLayers effective = layers == null ? ModuleSwitchLayers.BOTH : layers;

        List<String> touchedGates = new ArrayList<>();
        if (effective.touchesBackend()) {
            for (String gateCode : entry.gateCodes()) {
                gateToggle.toggle(gateCode);
                touchedGates.add(gateCode);
            }
        }
        boolean touchedFrontend = false;
        if (effective.touchesFrontend()) {
            upsertVisibility(factoryId, entry, enabled);
            touchedFrontend = true;
        }

        log.info("模块开关: factory={} module={} enabled={} layers={} gates={} frontend={}",
                factoryId, moduleId, enabled, effective, touchedGates, touchedFrontend);

        return new ModuleSwitchResult(moduleId, entry.label(), enabled, effective,
                touchedGates, touchedFrontend, describeOne(factoryId, entry));
    }

    /**
     * 每个模块在两层上的组合状态。界面据此渲染, 并只对
     * {@link ModuleSwitchState#MISCONFIGURED} 报红。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> describe(String factoryId) {
        // 🔴 「客户关掉了模块, 但改动停在未发布的草稿里」—— 这一格此前完全说不出来:
        //    四态会把它算成 ROLLOUT(隐藏+开放 = 灰度中), 那是**准确但不完整**的描述,
        //    客户看到「灰度中 · 接口已开放」会以为一切正常。
        //    ⇒ 从 describeModuleGate 取这条信息, 并写进【页面已经在渲染的那个 stateDescription】,
        //      所以不需要改任何 .vue —— 见 web-admin features 页的 :title 绑定。
        ModuleGateStatus gate = factoryConfigService.describeModuleGate(factoryId);
        Set<String> pendingCodes = new HashSet<>(gate.pendingPublishModuleCodes());

        List<Map<String, Object>> out = new ArrayList<>();
        for (InterviewModuleConfigCatalog.Entry e : InterviewModuleConfigCatalog.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("moduleId", e.moduleId());
            row.put("label", e.label());
            row.put("carrier", e.carrier());
            row.put("gateCodes", e.gateCodes());
            ModuleSwitchState state = describeOne(factoryId, e);
            row.put("frontendVisible", isVisible(factoryId, e.moduleId()));
            row.put("backendEnabled", isBackendEnabled(factoryId, e));
            row.put("state", state.name());

            // 这个模块的门禁码里, 有没有哪一个是「关了但没发布」
            boolean pendingPublish = e.gateCodes().stream().anyMatch(pendingCodes::contains);
            row.put("pendingPublish", pendingPublish);
            row.put("stateDescription", pendingPublish
                    ? state.getDescription() + " ⚠️ 你已经关掉了这个模块, 但改动停在未发布的草稿里, "
                      + "所以【当前还没生效】—— 请到「画布配置」点一次「立即发布」。"
                    : state.getDescription());
            // ⛔ needsAttention 的口径【没有改】: 要不要让「未发布」也在界面上报红,
            //    是「客户看到的应该是什么」, 属产品决定。数据已经在 pendingPublish 里,
            //    真要报红只需把下面这行改成 `state.isNeedsAttention() || pendingPublish`。
            row.put("needsAttention", state.isNeedsAttention());
            // ⛔ 后端没上闸的模块(smartbi / trace / system), gateCodes 为空 ——
            //    它们的「后端开放」恒为 true, 不是因为门禁放行, 是因为压根没有门禁。
            row.put("backendGated", !e.gateCodes().isEmpty());
            out.add(row);
        }
        return out;
    }

    private ModuleSwitchState describeOne(String factoryId, InterviewModuleConfigCatalog.Entry e) {
        return ModuleSwitchState.of(isVisible(factoryId, e.moduleId()), isBackendEnabled(factoryId, e));
    }

    /**
     * 前端可见性。⚠️ 没有行 = 可见 —— 与 RN {@code factoryFeatureStore} 的
     * {@code mod ? mod.enabled : true} 同口径, 不是我另发明的默认值。
     */
    private boolean isVisible(String factoryId, String moduleId) {
        return featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, moduleId)
                .map(FactoryFeatureConfig::getEnabled)
                .orElse(true);
    }

    /** 后端门禁:任一门禁码开着就算开着(全关才算关)。无门禁码的模块恒为开。 */
    private boolean isBackendEnabled(String factoryId, InterviewModuleConfigCatalog.Entry e) {
        if (e.gateCodes().isEmpty()) {
            return true;
        }
        for (String gateCode : e.gateCodes()) {
            if (factoryConfigService.isModuleEnabled(factoryId, gateCode)) {
                return true;
            }
        }
        return false;
    }

    private void upsertVisibility(String factoryId, InterviewModuleConfigCatalog.Entry entry, boolean enabled) {
        FactoryFeatureConfig cfg = findExistingVisibility(factoryId, entry.moduleId())
                .orElseGet(() -> FactoryFeatureConfig.builder()
                        .factoryId(factoryId)
                        .moduleId(entry.moduleId())
                        .moduleName(entry.label())
                        .config(new HashMap<>())
                        .build());
        cfg.setEnabled(enabled);
        cfg.setDeletedAt(null);
        cfg.setUpdatedAt(LocalDateTime.now());
        featureConfigRepository.save(cfg);
    }

    private java.util.Optional<FactoryFeatureConfig> findExistingVisibility(String factoryId, String moduleId) {
        java.util.Optional<FactoryFeatureConfig> active = featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, moduleId);
        if (active.isPresent()) {
            return active;
        }

        FactoryFeatureConfig probe = new FactoryFeatureConfig();
        probe.setFactoryId(factoryId);
        probe.setModuleId(moduleId);
        ExampleMatcher matcher = ExampleMatcher.matching().withIgnorePaths(
                "id", "moduleName", "enabled", "config", "conversationSummary",
                "createdAt", "updatedAt", "deletedAt");
        return featureConfigRepository.findOne(Example.of(probe, matcher));
    }

    /** 一次开关操作的结果 —— 显式说清楚动了哪一层, 别让调用方猜。 */
    public record ModuleSwitchResult(String moduleId,
                                     String label,
                                     boolean enabled,
                                     ModuleSwitchLayers layers,
                                     List<String> touchedGateCodes,
                                     boolean touchedFrontend,
                                     ModuleSwitchState state) {}
}
