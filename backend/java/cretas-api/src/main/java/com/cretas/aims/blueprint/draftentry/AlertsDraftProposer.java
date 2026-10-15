package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftCommand;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.Comparison;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DraftRequest;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.Metric;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.ModuleConfigPin;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.PolicyInput;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.RecipientRole;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.Severity;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.blueprint.interview.InterviewAlertCatalog;
import com.cretas.aims.blueprint.interview.InterviewAnomalyMetricCatalog;
import com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code alerts} proposal: alert policies for the blueprint facts that name one of the command's
 * closed metrics, pinned to the latest same-lineage module-config DRAFT.
 *
 * <p>The interview has three alert-ish facts and the command has nine closed metrics; most of the
 * facts do not land on a metric, and those are <b>not</b> turned into a policy:</p>
 * <ul>
 *   <li>{@code suggestedData.alerts} are event alerts (low stock, overdue payment, large PO …). None
 *       of them is a measured metric the command knows, so none is carried.</li>
 *   <li>{@code suggestedData.metricWatches}: only {@code yield_rate} (良品率 = good/actual × 100 on a
 *       batch) is the command's {@link Metric#BATCH_YIELD_PERCENT}; its direction comes from the
 *       interview catalog. The cost/quantity/duration watches have no metric.</li>
 *   <li>{@code suggestedData.thresholds}: the cold-chain temperature ceiling and the humidity floor
 *       and ceiling are the command's cold-storage temperature and room humidity metrics.</li>
 * </ul>
 * <p>Facts that are not carried are listed in the evidence as "未带入" with the reason, so the user
 * sees what the interview said that this draft does not cover.</p>
 *
 * <p>The interview never asked for the evaluation window, consecutive breaches, severity,
 * recipients, suppression, or which module a policy belongs to; those are left empty and asked.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class AlertsDraftProposer implements CapabilityDraftProposer {

    static final String UPSTREAM_MISSING = "BLUEPRINT_DRAFT_UPSTREAM_MISSING";
    static final String NOT_CARRIED = "未带入：";

    /** Threshold key → the metric and direction the key itself states (a "max" is breached above it). */
    private static final Map<String, Mapped> THRESHOLD_METRICS = Map.of(
            ThresholdKeys.IOT_COLD_CHAIN_TEMP_MAX, new Mapped(Metric.COLD_STORAGE_TEMPERATURE, Comparison.GREATER_THAN, "冷链温度高于上限"),
            ThresholdKeys.IOT_HUMIDITY_MAX, new Mapped(Metric.ROOM_HUMIDITY, Comparison.GREATER_THAN, "环境湿度高于上限"),
            ThresholdKeys.IOT_HUMIDITY_MIN, new Mapped(Metric.ROOM_HUMIDITY, Comparison.LESS_THAN, "环境湿度低于下限"));
    /** Metric-watch name → metric. The direction is the interview catalog's, not restated here. */
    private static final Map<String, Metric> WATCH_METRICS = Map.of("yield_rate", Metric.BATCH_YIELD_PERCENT);
    private static final String RECIPIENT_VALUES = String.join("、",
            Arrays.stream(RecipientRole.values()).map(Enum::name).toList());
    static final List<Option> RECIPIENT_OPTIONS = Option.ofEnum(RecipientRole.class, Map.of(
            RecipientRole.QUALITY_INSPECTOR, "质检员", RecipientRole.QUALITY_MANAGER, "质量经理",
            RecipientRole.PRODUCTION_SUPERVISOR, "生产主管", RecipientRole.WAREHOUSE_SUPERVISOR, "仓库主管",
            RecipientRole.MAINTENANCE_OWNER, "设备维护负责人"));
    static final List<Option> SEVERITY_OPTIONS = Option.ofEnum(Severity.class, Map.of(
            Severity.INFO, "提示", Severity.WARNING, "警告", Severity.CRITICAL, "严重"));
    /** The command's limits ({@code ALERT_POLICY_WINDOW_INVALID}, {@code ALERT_POLICY_RECIPIENTS_INVALID}). */
    static final int MAX_WINDOW_MINUTES = 1440;
    static final int MAX_CONSECUTIVE_BREACHES = 20;
    static final int MAX_RECIPIENTS = 4;
    /** The command's row limits ({@code policies} non-empty, at most {@code MAX_POLICIES}) and field lengths. */
    static final int MIN_POLICIES = 1;
    static final int MAX_POLICIES = 300;
    static final int MAX_POLICY_CODE = 60;
    static final int MAX_TITLE = 200;
    static final int MAX_REASON = 500;
    static final List<Option> METRIC_OPTIONS = Option.ofEnum(Metric.class, Map.of(
            Metric.COLD_STORAGE_TEMPERATURE, "冷库温度", Metric.PROCESS_CORE_TEMPERATURE, "加工中心温度",
            Metric.ROOM_HUMIDITY, "环境湿度", Metric.EQUIPMENT_DOWNTIME_MINUTES, "设备停机分钟数",
            Metric.BATCH_YIELD_PERCENT, "批次良品率", Metric.QUALITY_FAIL_RATE_PERCENT, "质检不合格率",
            Metric.INVENTORY_DAYS_ON_HAND, "库存可用天数", Metric.OVERDUE_CALIBRATION_COUNT, "逾期未校准数",
            Metric.OVERDUE_SANITATION_COUNT, "逾期未清洁数"));
    static final List<Option> COMPARISON_OPTIONS = Option.ofEnum(Comparison.class, Map.of(
            Comparison.GREATER_THAN, "高于", Comparison.GREATER_OR_EQUAL, "不低于", Comparison.LESS_THAN, "低于",
            Comparison.LESS_OR_EQUAL, "不高于", Comparison.OUTSIDE_RANGE, "超出区间"));

    private final ModuleConfigDraftRepository moduleDrafts;
    private final ModuleConfigDraftItemRepository moduleItems;

    public AlertsDraftProposer(ModuleConfigDraftRepository moduleDrafts, ModuleConfigDraftItemRepository moduleItems) {
        this.moduleDrafts = moduleDrafts;
        this.moduleItems = moduleItems;
    }

    @Override
    public String capabilityId() {
        return AlertPolicyDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        ModuleConfigDraft module = UpstreamDraftPins.latestModuleConfig(moduleDrafts, source).orElse(null);
        if (module == null) {
            return Proposed.blocked(UPSTREAM_MISSING,
                    "告警策略必须绑定本蓝图同一版本的模块配置草稿，但还没有写模块配置草稿",
                    "先写模块配置草稿");
        }
        var blueprint = source.blueprint();
        ApplyAIInitRequest.SuggestedData data = blueprint.getSourcePayload().getSuggestedData();
        List<String> enabled = UpstreamDraftPins.enabledModules(moduleItems, source.factoryId(), module.getId());
        List<PolicyInput> policies = new ArrayList<>();
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("moduleConfig", module.getId() + " / " + module.getDesiredDigest()));
        String reasonPrefix = "依据建厂蓝图 v" + blueprint.getVersion() + "：";

        if (data != null && data.getAlerts() != null) {
            for (int i = 0; i < data.getAlerts().size(); i++) {
                ApplyAIInitRequest.AlertItem alert = data.getAlerts().get(i);
                if (alert == null || Boolean.FALSE.equals(alert.getEnabled())) continue;
                InterviewAlertCatalog.Entry entry = InterviewAlertCatalog.find(alert.getAlertType());
                evidence.add(new Evidence("sourcePayload.suggestedData.alerts[" + i + "].alertType",
                        NOT_CARRIED + (entry == null ? alert.getAlertType() : entry.label())
                                + "是事件告警，告警策略的指标里没有对应项"));
            }
        }
        if (data != null && data.getMetricWatches() != null) {
            for (int i = 0; i < data.getMetricWatches().size(); i++) {
                ApplyAIInitRequest.MetricWatchItem watch = data.getMetricWatches().get(i);
                if (watch == null || Boolean.FALSE.equals(watch.getEnabled())) continue;
                String at = "sourcePayload.suggestedData.metricWatches[" + i + "]";
                InterviewAnomalyMetricCatalog.Entry entry = InterviewAnomalyMetricCatalog.find(watch.getMetricName());
                Metric metric = entry == null ? null : WATCH_METRICS.get(entry.metricName());
                if (metric == null) {
                    evidence.add(new Evidence(at + ".metricName", NOT_CARRIED
                            + (entry == null ? watch.getMetricName() : entry.label()) + "在告警策略的指标里没有对应项"));
                    continue;
                }
                evidence.add(new Evidence(at, entry.metricName() + " " + entry.comparison() + " "
                        + (watch.getThreshold() == null ? "（无阈值）" : watch.getThreshold().toPlainString())));
                Comparison comparison = Comparison.valueOf(entry.comparison());
                String direction = comparison == Comparison.LESS_THAN ? "过低提醒" : "过高提醒";
                policies.add(policy(policies.size(), "MW." + entry.metricName(), entry.label() + direction, metric,
                        comparison, watch.getThreshold(), "访谈要求盯“" + entry.label() + "”",
                        reasonPrefix, moduleOptions(enabled), inputs));
            }
        }
        if (data != null && data.getThresholds() != null) {
            for (int i = 0; i < data.getThresholds().size(); i++) {
                ApplyAIInitRequest.ThresholdItem threshold = data.getThresholds().get(i);
                if (threshold == null || threshold.getKey() == null) continue;
                String key = threshold.getKey().trim();
                String at = "sourcePayload.suggestedData.thresholds[" + i + "]";
                Mapped mapped = THRESHOLD_METRICS.get(key);
                if (mapped == null) {
                    evidence.add(new Evidence(at + ".key", NOT_CARRIED + key + "在告警策略的指标里没有对应项"));
                    continue;
                }
                evidence.add(new Evidence(at, key + "=" + threshold.getValue()));
                policies.add(policy(policies.size(), "TH." + key, mapped.title(), mapped.metric(), mapped.comparison(),
                        decimal(threshold.getValue()), "访谈设定预警阈值 " + key, reasonPrefix, moduleOptions(enabled), inputs));
            }
        }

        if (enabled.isEmpty()) {
            // Every policy's module — a proposed one's or a row the user adds — would be asked with nothing to choose from.
            return Proposed.blocked("BLUEPRINT_DRAFT_NO_ENABLED_MODULE",
                    "告警策略必须归到模块配置草稿里已启用的模块，但模块配置草稿 " + module.getId() + " 没有启用任何模块",
                    "先在模块配置草稿里启用要用的模块");
        }
        if (policies.isEmpty()) {
            inputs.add(RequiredInput.list("policies", "要建哪些告警策略",
                    "建厂蓝图里没有能对应到告警策略指标（温湿度、良品率等）的事实",
                    MIN_POLICIES + "–" + MAX_POLICIES + " 条策略，策略编码不能重复", "告警策略",
                    MIN_POLICIES, MAX_POLICIES, policyFields(moduleOptions(enabled))));
        }
        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), new ModuleConfigPin(module.getId(), module.getDesiredDigest()),
                policies.isEmpty() ? null : policies, null, null, 0L);
        return Proposed.single(capabilityId(), "告警策略", request, inputs, evidence);
    }

    private static PolicyInput policy(int index, String code, String title, Metric metric, Comparison comparison,
                                      BigDecimal threshold, String why, String reasonPrefix, List<Option> modules,
                                      List<RequiredInput> inputs) {
        String at = "policies[" + index + "].";
        String name = "“" + title + "”";
        if (threshold == null) {
            inputs.add(RequiredInput.decimal(at + "thresholdValue", name + "的阈值是多少",
                    "建厂蓝图里这一项没有可用的数字阈值", "数字，整数部分不超过 12 位、小数不超过 6 位", null, null));
        }
        inputs.add(RequiredInput.oneOf(at + "moduleId", name + "归哪个模块管",
                "建厂访谈没有把告警分到模块",
                "模块配置草稿中已启用的模块之一：" + String.join("、", modules.stream().map(Option::value).toList()),
                modules));
        inputs.add(RequiredInput.integer(at + "evaluationWindowMinutes", name + "按多长时间窗口判断",
                "建厂访谈没有问评估窗口", "1–" + MAX_WINDOW_MINUTES + " 分钟", 1, MAX_WINDOW_MINUTES));
        inputs.add(RequiredInput.integer(at + "consecutiveBreaches", name + "连续超限几次才提醒",
                "建厂访谈没有问连续次数", "1–" + MAX_CONSECUTIVE_BREACHES + " 次", 1, MAX_CONSECUTIVE_BREACHES));
        inputs.add(RequiredInput.oneOf(at + "severity", name + "有多严重",
                "建厂访谈没有问严重程度",
                "INFO、WARNING、CRITICAL 之一（CRITICAL 时接收人必须含 QUALITY_MANAGER，且不能设抑制窗口）",
                SEVERITY_OPTIONS));
        inputs.add(RequiredInput.manyOf(at + "recipientRoles", name + "通知哪些角色",
                "建厂访谈的通知角色是站内信账号角色，与告警策略的接收角色不是同一套",
                "1–" + MAX_RECIPIENTS + " 个不重复角色，可选：" + RECIPIENT_VALUES, RECIPIENT_OPTIONS, 1, MAX_RECIPIENTS));
        inputs.add(RequiredInput.integer(at + "suppressionWindowMinutes", name + "提醒后多久内不重复提醒",
                "建厂访谈没有问抑制窗口", "0–" + MAX_WINDOW_MINUTES + " 分钟（CRITICAL 必须为 0）", 0, MAX_WINDOW_MINUTES));
        return new PolicyInput(code.toUpperCase(Locale.ROOT), title, null, metric, comparison, threshold,
                // Only OUTSIDE_RANGE carries an upper bound (command rule); every mapping here is one-sided.
                null, null, null, null, List.of(), null, reasonPrefix + why);
    }

    /**
     * One row of {@code policies}: every component of {@link PolicyInput}, with the command's own rules.
     * The module choices are the pinned module DRAFT's enabled modules, so the list is built per proposal.
     */
    static List<RequiredInput> policyFields(List<Option> modules) {
        String why = "建厂蓝图里没有能对应到告警策略指标的事实";
        return List.of(
                RequiredInput.text("policyCode", "策略编码", why,
                        "大写字母或数字开头，只含大写字母、数字、下划线、点或连字符，最长 " + MAX_POLICY_CODE + " 位；草稿内不重复",
                        MAX_POLICY_CODE),
                RequiredInput.text("title", "策略名称", why, "1–" + MAX_TITLE + " 字", MAX_TITLE),
                RequiredInput.oneOf("moduleId", "归哪个模块管", why,
                        "模块配置草稿中已启用的模块之一：" + String.join("、", modules.stream().map(Option::value).toList()),
                        modules),
                RequiredInput.oneOf("metric", "盯哪个指标", why,
                        String.join("、", METRIC_OPTIONS.stream().map(Option::value).toList()) + " 之一", METRIC_OPTIONS),
                RequiredInput.oneOf("comparison", "怎么比较", why,
                        String.join("、", COMPARISON_OPTIONS.stream().map(Option::value).toList())
                                + " 之一（OUTSIDE_RANGE 要同时填上界）", COMPARISON_OPTIONS),
                RequiredInput.decimal("thresholdValue", "阈值", why,
                        "数字，整数部分不超过 12 位、小数不超过 6 位；选 OUTSIDE_RANGE 时是区间下界", null, null),
                RequiredInput.decimal("upperThresholdValue", "区间上界", why,
                        "只有选 OUTSIDE_RANGE 时必填且大于阈值，其他比较方式留空；整数部分不超过 12 位、小数不超过 6 位",
                        null, null).allowNull(),
                RequiredInput.integer("evaluationWindowMinutes", "按多长时间窗口判断", why,
                        "1–" + MAX_WINDOW_MINUTES + " 分钟", 1, MAX_WINDOW_MINUTES),
                RequiredInput.integer("consecutiveBreaches", "连续超限几次才提醒", why,
                        "1–" + MAX_CONSECUTIVE_BREACHES + " 次", 1, MAX_CONSECUTIVE_BREACHES),
                RequiredInput.oneOf("severity", "有多严重", why,
                        "INFO、WARNING、CRITICAL 之一（CRITICAL 时接收人必须含 QUALITY_MANAGER，且不能设抑制窗口）",
                        SEVERITY_OPTIONS),
                RequiredInput.manyOf("recipientRoles", "通知哪些角色", why,
                        "1–" + MAX_RECIPIENTS + " 个不重复角色，可选：" + RECIPIENT_VALUES, RECIPIENT_OPTIONS, 1, MAX_RECIPIENTS),
                RequiredInput.integer("suppressionWindowMinutes", "提醒后多久内不重复提醒", why,
                        "0–" + MAX_WINDOW_MINUTES + " 分钟（CRITICAL 必须为 0）", 0, MAX_WINDOW_MINUTES),
                RequiredInput.text("changeReason", "建这条策略的依据", why, "1–" + MAX_REASON + " 字", MAX_REASON));
    }

    /** Enabled module ids of the pinned module DRAFT, labelled with the module catalog's name. */
    static List<Option> moduleOptions(List<String> enabled) {
        return enabled.stream().map(id -> {
            InterviewModuleConfigCatalog.Entry entry = InterviewModuleConfigCatalog.find(id);
            return new Option(id, entry == null ? id : entry.label());
        }).toList();
    }

    /** The threshold fact is a string; a value that is not a number is not a threshold. */
    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private record Mapped(Metric metric, Comparison comparison, String title) {
    }
}
