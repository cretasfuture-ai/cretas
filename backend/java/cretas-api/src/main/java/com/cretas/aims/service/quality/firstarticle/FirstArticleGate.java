package com.cretas.aims.service.quality.firstarticle;

import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.quality.firstarticle.FirstArticlePolicy.Mode;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleStandard;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleInspectionRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticlePolicyRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleStandardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 工序首检在开工 / 完工路径上的闸。<b>只在工厂开了首检开关时生效</b>:
 * <ul>
 *   <li>{@link Mode#OFF} (默认, 没有开关行也是 OFF): 只查一次开关就返回, 不查标准、不查首检单, 开工/完工与没有这项功能时完全一样;</li>
 *   <li>{@link Mode#WARN}: 不拦, 开工/完工返回里带一句 {@code firstArticleNotice};</li>
 *   <li>{@link Mode#BLOCK}: 报完工时首检没合格 → 409 {@value #CODE}; 开工不拦 (首件要开工后才做得出来)。</li>
 * </ul>
 * 调用方: {@code WorkProcessTaskServiceImpl#start / #complete} (HTTP + AI 完工工具都走它),
 * AI 完工预览经 {@code WorkProcessTaskService#assertFirstArticleAllowsComplete} 镜像同一判据。
 *
 * <p>⚠️ 本切片未接: Web 逐道录入 ({@code ProcessSheetServiceImpl} → {@code WorkflowTaskProgressWriter}) 与 App 产量报工
 * 把任务记成完成的路径, BLOCK 不拦这两条 (见设计文档「未做」)。
 */
@Component
@RequiredArgsConstructor
public class FirstArticleGate {

    public static final String CODE = "FIRST_ARTICLE_NOT_PASSED";

    private final FirstArticlePolicyRepository policyRepository;
    private final FirstArticleStandardRepository standardRepository;
    private final FirstArticleInspectionRepository inspectionRepository;

    /** 本厂开关; 没有行 = OFF。 */
    public Mode modeOf(String factoryId) {
        Mode m = policyRepository.findModeByFactoryId(factoryId);
        return m == null ? Mode.OFF : m;
    }

    /** 该任务当前的首检要求与状态; 工序没有启用标准 → empty (不要求首检)。不看开关。 */
    public Optional<Requirement> requirementOf(String factoryId, WorkProcessTask task) {
        if (task == null || task.getWorkProcessId() == null) {
            return Optional.empty();
        }
        Optional<FirstArticleStandard> std = standardRepository.findActive(factoryId, task.getWorkProcessId());
        if (std.isEmpty()) {
            return Optional.empty();
        }
        List<FirstArticleInspection> effective =
                inspectionRepository.findEffectiveForTasks(factoryId, List.of(task.getId()));
        FirstArticleInspection latest = effective.isEmpty() ? null : effective.get(0);
        return Optional.of(new Requirement(std.get(), latest));
    }

    /** WARN / BLOCK 下首检还没合格时给人看的一句话; OFF、不要求、已合格 → null。 */
    public String noticeFor(String factoryId, WorkProcessTask task) {
        Mode mode = modeOf(factoryId);
        if (mode == Mode.OFF) {
            return null;
        }
        return requirementOf(factoryId, task)
                .filter(r -> !r.passed())
                .map(r -> "这道工序要做首检 (标准「" + r.standard().getName() + "」), 现在" + r.stateText()
                        + (mode == Mode.WARN ? "; 本厂设置为只提示, 不影响报工" : "; 首检合格前不能报完工"))
                .orElse(null);
    }

    /** BLOCK 下首检没合格 → 409; OFF / WARN / 不要求 / 已合格 → 放行。 */
    public void assertCanComplete(String factoryId, WorkProcessTask task) {
        if (modeOf(factoryId) != Mode.BLOCK) {
            return;
        }
        Optional<Requirement> req = requirementOf(factoryId, task);
        if (req.isEmpty() || req.get().passed()) {
            return;
        }
        Requirement r = req.get();
        throw new BusinessException(409, "这道工序首检还没合格 (标准「" + r.standard().getName() + "」, 现在"
                + r.stateText() + "), 本厂设置为首检合格前不能报完工")
                .withCode(CODE)
                .withHint("请先送检, 由质检判定首检合格后再报完工")
                .withSeverity("BLOCKING");
    }

    /** {@code latest}: 最近一张非作废的首检单, null = 还没送检。 */
    public record Requirement(FirstArticleStandard standard, FirstArticleInspection latest) {
        public boolean passed() {
            return latest != null && latest.getStatus() == FirstArticleInspection.Status.PASSED;
        }

        public String stateText() {
            if (latest == null) return "还没送检";
            return switch (latest.getStatus()) {
                case PENDING -> "已送检、等质检判定";
                case FAILED -> "首检不合格";
                case PASSED -> "首检合格";
                case VOID -> "还没送检";
            };
        }
    }
}
