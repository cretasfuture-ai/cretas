package com.cretas.aims.service.production;

import com.cretas.aims.entity.enums.DefectCountOutcome;
import com.cretas.aims.exception.BusinessException;

import java.math.BigDecimal;

/**
 * 结单「这批有没有不良品」三态结论 → 落库值，的纯函数。
 *
 * <h2>为什么抽成纯函数</h2>
 *
 * <p>这段判定原本长在 {@code ProductionPlanServiceImpl}（479KB、要起半个 Spring 上下文）
 * 的一个私有方法里 —— <b>测试够不着它</b>。而「仪器够不着被测行为」与「行为没生效」
 * 的读数一模一样、结论却相反，本仓已为此栽过。⇒ 抽出来，让它可以被直接调用。</p>
 *
 * <h2>它守的是什么</h2>
 *
 * <p><b>「没回答」不许被翻译成「零不良」。</b> 2026-08-26 生产实测：72 张报工单
 * 零填写，而旧代码兜底填 0，于是 8 个已完工批次全部声称 100% 良品 —— 其中 4 个的
 * 质检明确记了不合格（批次 10766：抽样 80 / 不合格 5）。</p>
 */
public final class DefectCountResolver {

    private DefectCountResolver() {
    }

    /** 落库用的一对值。{@code outcome == null} 表示这次结单没被问过（存量/旧客户端）。 */
    public record Resolved(DefectCountOutcome outcome, BigDecimal quantity) {
    }

    /**
     * 校验结论与数量自洽，并归一化成落库值。
     *
     * @throws BusinessException 400 —— 结论与数量矛盾时。⛔ 不做静默纠正：
     *         悄悄把矛盾的输入改成一个「看起来合理」的值，正是编数据的开始。
     */
    public static Resolved resolve(DefectCountOutcome outcome, BigDecimal quantity) {
        if (outcome == null) {
            // 旧客户端 / 尚未升级的入口 —— 按「没清点」记, 数量留 null。
            // ⛔ 不回填 0: 那会假装有人清点过并确认了零不良。
            return new Resolved(null, null);
        }
        return switch (outcome) {
            case COUNTED -> {
                if (quantity == null || quantity.signum() <= 0) {
                    throw new BusinessException(400, "选择了「有不良品」就必须填数量, 且必须大于 0")
                            .withHint("若这批确认零不良, 请改选「已清点, 无不良品」");
                }
                yield new Resolved(DefectCountOutcome.COUNTED, quantity);
            }
            case NONE_CONFIRMED -> {
                if (quantity != null && quantity.signum() != 0) {
                    throw new BusinessException(400,
                            "选择了「已清点, 无不良品」就不能同时填一个不为 0 的数量")
                            .withHint("确有不良品请改选「有不良品」并填写数量");
                }
                // 这个 0 是【真实读数】—— 有人清点过并确认了。
                // 与 NOT_COUNTED 的 null 严格区分, 两者对良品率的含义相反。
                yield new Resolved(DefectCountOutcome.NONE_CONFIRMED, BigDecimal.ZERO);
            }
            case NOT_COUNTED -> {
                if (quantity != null) {
                    throw new BusinessException(400, "选择了「未清点」就不能填不良品数量")
                            .withHint("填了数量说明清点过, 请改选对应的结论");
                }
                yield new Resolved(DefectCountOutcome.NOT_COUNTED, null);
            }
        };
    }
}
