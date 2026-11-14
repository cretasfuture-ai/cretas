package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.entity.intent.IntentFlywheelTurn;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.Action;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.InputKind;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.Reaction;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户反应: 用<b>同一会话的下一轮</b>判上一轮 (以及确认入口判写确认)。只写能确定的; 判不了写 {@link Reaction#UNKNOWN},
 * 没有下一轮就留空 (导出时按时间窗推导 ABANDONED / NO_FOLLOWUP / PENDING) —— ⛔ 不把不知道写成别的值。
 *
 * <table>
 *   <tr><th>上一轮</th><th>下一轮</th><th>反应</th></tr>
 *   <tr><td>出按钮 / 追问 (NEED_CLARIFICATION / NEED_MORE_INFO / 动作 SHOW / ASK)</td>
 *       <td>点了按钮 (请求带 intentCode)</td>
 *       <td>在上一轮候选里 → OPTION_SELECTED + 第几项 (1 起); 不在 → OPTION_OUTSIDE_CANDIDATES</td></tr>
 *   <tr><td>同上</td><td>打字, 识别出的意图在上一轮候选里; 或上一轮是追问参数 (NEED_MORE_INFO)、这一轮没有重新识别 (续上了)</td>
 *       <td>ANSWERED_CLARIFICATION (追问后直接答)</td></tr>
 *   <tr><td>同上</td><td>打字, 识别出别的意图</td><td>CHANGED_REQUEST (改说法 / 换了件事)</td></tr>
 *   <tr><td>写确认 (WRITE_CONFIRM_REQUIRED / NEED_CONFIRMATION)</td><td>确认入口收到同一张确认卡 (commandDigest)</td>
 *       <td>CONFIRMED</td></tr>
 *   <tr><td>写确认</td><td>没确认就说了下一句</td><td>CONFIRM_SKIPPED (取消在 App 本地, 服务端看不到, 不写成「取消」)</td></tr>
 *   <tr><td>直接办完 (COMPLETED / SUCCESS)</td><td>同一个意图</td><td>SAME_INTENT_FOLLOWUP (多半是改条件)</td></tr>
 *   <tr><td>直接办完</td><td>别的意图</td><td>MOVED_ON (接着办别的; 不等于「满意」)</td></tr>
 *   <tr><td>其余组合</td><td>-</td><td>UNKNOWN</td></tr>
 * </table>
 */
public final class FlywheelReactionRules {

    /** 相隔超过这个时间的下一轮不算「反应」。 */
    public static final Duration WINDOW = Duration.ofMinutes(30);

    static final Set<String> CLARIFY_STATUSES = Set.of("NEED_CLARIFICATION", "NEED_MORE_INFO");
    static final Set<String> CONFIRM_STATUSES = Set.of("WRITE_CONFIRM_REQUIRED", "NEED_CONFIRMATION");
    static final Set<String> DONE_STATUSES = Set.of("COMPLETED", "SUCCESS");

    /** 「这一轮没有重新识别」的决策来源 (续上了上一轮的追问)。 */
    static final String NO_RECOGNITION = "NO_RECOGNITION";

    private FlywheelReactionRules() {
    }

    public record Verdict(Reaction reaction, Integer optionRank) {
    }

    /**
     * @param prev          上一轮 (同一工厂 / 用户 / 会话, 尚未判过)
     * @param prevCandidates 上一轮的候选意图码 (按展示顺序)
     * @param cur           这一轮
     */
    public static Verdict judge(IntentFlywheelTurn prev, List<String> prevCandidates, IntentFlywheelTurn cur) {
        if (prev == null || cur == null) {
            return null;
        }
        String status = prev.getFinalStatus() == null ? "" : prev.getFinalStatus();
        boolean clarify = CLARIFY_STATUSES.contains(status)
                || prev.getDecisionAction() == Action.SHOW || prev.getDecisionAction() == Action.ASK;
        if (CONFIRM_STATUSES.contains(status)) {
            return new Verdict(Reaction.CONFIRM_SKIPPED, null);
        }
        List<String> candidates = prevCandidates == null ? List.of() : prevCandidates;
        if (clarify) {
            if (cur.getInputKind() == InputKind.BUTTON) {
                int idx = candidates.indexOf(cur.getRequestedIntentCode());
                return idx >= 0 ? new Verdict(Reaction.OPTION_SELECTED, idx + 1)
                        : new Verdict(Reaction.OPTION_OUTSIDE_CANDIDATES, null);
            }
            String decided = cur.getDecidedIntentCode();
            if (decided != null && candidates.contains(decided)) {
                return new Verdict(Reaction.ANSWERED_CLARIFICATION, null);
            }
            if ("NEED_MORE_INFO".equals(status) && NO_RECOGNITION.equals(cur.getDecisionSource())) {
                return new Verdict(Reaction.ANSWERED_CLARIFICATION, null);
            }
            if (decided != null) {
                return new Verdict(Reaction.CHANGED_REQUEST, null);
            }
            return new Verdict(Reaction.UNKNOWN, null);
        }
        if (DONE_STATUSES.contains(status)) {
            String before = prev.getResponseIntentCode() != null ? prev.getResponseIntentCode() : prev.getDecidedIntentCode();
            String after = cur.getResponseIntentCode() != null ? cur.getResponseIntentCode() : cur.getDecidedIntentCode();
            if (before == null || after == null) {
                return new Verdict(Reaction.UNKNOWN, null);
            }
            return new Verdict(Objects.equals(before, after) ? Reaction.SAME_INTENT_FOLLOWUP : Reaction.MOVED_ON, null);
        }
        return new Verdict(Reaction.UNKNOWN, null);
    }
}
