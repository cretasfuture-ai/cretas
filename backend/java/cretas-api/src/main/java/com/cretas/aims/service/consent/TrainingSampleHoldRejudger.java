package com.cretas.aims.service.consent;

import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import com.cretas.aims.repository.learning.TrainingSampleSecrecyHoldRepository;
import com.cretas.aims.service.ActiveLearningService;
import com.cretas.aims.service.ExpressionLearningService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 按<b>当前</b>本厂名单重判挂起的训练样本 (待办第 2 条「相关行挂起重判」)。
 *
 * <p>每条挂起样本:
 * <ul>
 *   <li>名单这次查得出、没提到保密词 / 人员 / 客户 → 重新走一遍原来的收样本入口 ({@code recordSample} /
 *       {@code collectSample}: 内容规则、训练授权 (撤回的不存、授权降级的重新脱敏)、名单都再判一次) 写进样本表, 删挂起行;</li>
 *   <li>提到了 → 删挂起行 (名单正常时它本来就不会被收);</li>
 *   <li>还是判断不了 → 记一次尝试; 挂起超过保留期 ({@code cretas.training.secrecy-hold.max-age}, 默认 7 天) 的删掉。</li>
 * </ul>
 * 两个蓝绿槽都会起这个任务, 用 ShedLock 只让一个跑。
 */
@Component
public class TrainingSampleHoldRejudger {

    private static final Logger log = LoggerFactory.getLogger(TrainingSampleHoldRejudger.class);

    static final int BATCH = 200;

    /** 一轮的结果 (测试和日志用)。 */
    public record Outcome(int released, int deleted, int stillHeld, int expired) {
    }

    private final TrainingSampleSecrecyHoldRepository holds;
    private final ProcessSecretSampleFilter filter;
    private final ExpressionLearningService expressionLearningService;
    private final ActiveLearningService activeLearningService;
    private final Duration maxAge;
    private final Clock clock;

    public TrainingSampleHoldRejudger(TrainingSampleSecrecyHoldRepository holds,
                                      ProcessSecretSampleFilter filter,
                                      @Lazy ExpressionLearningService expressionLearningService,
                                      @Lazy ActiveLearningService activeLearningService,
                                      @Value("${cretas.training.secrecy-hold.max-age:P7D}") Duration maxAge) {
        this(holds, filter, expressionLearningService, activeLearningService, maxAge, Clock.systemDefaultZone());
    }

    TrainingSampleHoldRejudger(TrainingSampleSecrecyHoldRepository holds, ProcessSecretSampleFilter filter,
                               ExpressionLearningService expressionLearningService,
                               ActiveLearningService activeLearningService, Duration maxAge, Clock clock) {
        this.holds = holds;
        this.filter = filter;
        this.expressionLearningService = expressionLearningService;
        this.activeLearningService = activeLearningService;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cretas.training.secrecy-hold.rejudge-interval-ms:600000}",
            initialDelayString = "${cretas.training.secrecy-hold.rejudge-initial-delay-ms:300000}")
    @SchedulerLock(name = "TrainingSampleHoldRejudger.rejudge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void scheduledRejudge() {
        Outcome o = rejudgeOnce();
        if (o.released() + o.deleted() + o.stillHeld() + o.expired() > 0) {
            log.info("挂起训练样本重判: released={}, deleted={}, stillHeld={}, expired={}",
                    o.released(), o.deleted(), o.stillHeld(), o.expired());
        }
    }

    /** 取最早挂起的一批重判一次。不开事务: 名单查询与每条写入各自一个事务, 一条出错不影响其余。 */
    public Outcome rejudgeOnce() {
        List<TrainingSampleSecrecyHold> batch = holds.findOldest(PageRequest.of(0, BATCH));
        int released = 0;
        int deleted = 0;
        int stillHeld = 0;
        int expired = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (TrainingSampleSecrecyHold h : batch) {
            try {
                ProcessSecretSampleFilter.SampleVerdict verdict = filter.checkTrainingSample(
                        "rejudge_" + h.getSampleKind().name().toLowerCase(), h.getFactoryId(), texts(h));
                switch (verdict) {
                    case CLEAR -> {
                        release(h);
                        holds.delete(h);
                        released++;
                    }
                    case PROTECTED -> {
                        holds.delete(h);
                        deleted++;
                    }
                    default -> {
                        if (h.getCreatedAt().plus(maxAge).isBefore(now)) {
                            holds.delete(h);
                            expired++;
                        } else {
                            h.setAttempts(h.getAttempts() + 1);
                            h.setLastAttemptAt(now);
                            holds.save(h);
                            stillHeld++;
                        }
                    }
                }
            } catch (Exception e) {
                // 坏的挂起行 (读不出 payload 等) 不能卡住整批; 留着, 到保留期由上面那条删
                log.warn("挂起训练样本重判失败, 留到下一轮: id={}, kind={}, factory={}, reason={}",
                        h.getId(), h.getSampleKind(), h.getFactoryId(), e.getClass().getSimpleName());
                stillHeld++;
            }
        }
        return new Outcome(released, deleted, stillHeld, expired);
    }

    private static String[] texts(TrainingSampleSecrecyHold h) throws Exception {
        if (h.getSampleKind() == TrainingSampleSecrecyHold.Kind.INTENT) {
            return new String[]{TrainingSampleHoldStore.intent(h).userInput()};
        }
        TrainingSampleHoldStore.ActiveLearningSampleData d = TrainingSampleHoldStore.activeLearning(h);
        return new String[]{d.userInput(), d.normalizedInput(), d.matchedKeywords(), d.topCandidates()};
    }

    /** 重新走原来的收样本入口 —— 写样本表只有这一条路, 不在这里另写一份。 */
    private void release(TrainingSampleSecrecyHold h) throws Exception {
        if (h.getSampleKind() == TrainingSampleSecrecyHold.Kind.INTENT) {
            TrainingSampleHoldStore.IntentSample s = TrainingSampleHoldStore.intent(h);
            expressionLearningService.recordSample(h.getFactoryId(), s.userInput(), s.intentCode(),
                    s.matchMethod() == null ? null : TrainingSample.MatchMethod.valueOf(s.matchMethod()),
                    s.confidence(), s.sessionId());
        } else {
            TrainingSampleHoldStore.ActiveLearningSampleData d = TrainingSampleHoldStore.activeLearning(h);
            activeLearningService.collectSample(h.getFactoryId(), d.userId(), d.userInput(), d.normalizedInput(),
                    d.matchedIntentCode(), d.confidenceScore(), d.matchMethod(), d.topCandidates(),
                    d.matchedKeywords(), d.sourceRecordId());
        }
    }
}
