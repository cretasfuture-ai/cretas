package com.cretas.aims.service.consent;

import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import com.cretas.aims.repository.learning.TrainingSampleSecrecyHoldRepository;
import com.cretas.aims.service.ActiveLearningService;
import com.cretas.aims.service.ExpressionLearningService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 按<b>当前</b>本厂名单重判挂起的训练样本 (待办第 2 条「相关行挂起重判」; Steve 2026-11-03 Q5; 复审 N3/N4)。
 *
 * <p>每条待判 (PENDING) 的挂起样本, 用它的<b>原话原文</b>判:
 * <ul>
 *   <li>名单查得出、没提到保密词 / 人员 / 客户 → <b>先删挂起行</b>, 再走原来的收样本入口 ({@code recordSample} /
 *       {@code collectSample}: 内容规则、训练授权、脱敏、名单都照常再走一遍) 写进样本表。先删再写: 写失败最多丢这一条,
 *       不会每轮重复写同一条 (N4b);</li>
 *   <li>提到了 → 标 REJECTED 并保留 (原因 + 时间), 留给人工复核, 不删 (Q5); 原话原文当场丢掉
 *       (复审第二轮第 1 条); 之后不再取它;</li>
 *   <li>还是判断不了、或挂起行本身读不出来 → 记一次尝试 (排到队尾, N4c); 挂起超过保留期
 *       ({@code cretas.training.secrecy-hold.max-age}, 默认 7 天) 的删掉 —— 读不出来的行同样到期删 (N4a)。</li>
 * </ul>
 * 两个蓝绿槽都会起这个任务, 用 ShedLock 只让一个跑。
 */
@Component
public class TrainingSampleHoldRejudger {

    private static final Logger log = LoggerFactory.getLogger(TrainingSampleHoldRejudger.class);

    static final int BATCH = 200;

    /** 一轮的结果 (测试和日志用)。 */
    public record Outcome(int released, int rejected, int stillHeld, int expired) {
    }

    private final TrainingSampleSecrecyHoldRepository holds;
    private final ProcessSecretSampleFilter filter;
    private final ExpressionLearningService expressionLearningService;
    private final ActiveLearningService activeLearningService;
    private final Duration maxAge;
    private final Clock clock;

    /** Spring 用这一个 (两个构造器时必须标明, 否则启动时找无参构造器 —— 2026-11-03 CI 抓到的启动失败)。 */
    @Autowired
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
        if (o.released() + o.rejected() + o.stillHeld() + o.expired() > 0) {
            log.info("挂起训练样本重判: released={}, rejected={}, stillHeld={}, expired={}",
                    o.released(), o.rejected(), o.stillHeld(), o.expired());
        }
    }

    /** 取下一批待判的挂起行重判一次。不开事务: 名单查询与每条写入各自一个事务, 一条出错不影响其余。 */
    public Outcome rejudgeOnce() {
        List<TrainingSampleSecrecyHold> batch = holds.findNextPending(PageRequest.of(0, BATCH));
        int released = 0;
        int rejected = 0;
        int stillHeld = 0;
        int expired = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (TrainingSampleSecrecyHold h : batch) {
            boolean isExpired = h.getCreatedAt().plus(maxAge).isBefore(now);
            String[] texts;
            try {
                texts = texts(h);
            } catch (Exception e) {
                // 读不出来的挂起行: 不能永远挂着 (N4a) —— 记尝试, 到期删
                log.warn("挂起训练样本读不出来: id={}, kind={}, factory={}, reason={}",
                        h.getId(), h.getSampleKind(), h.getFactoryId(), e.getClass().getSimpleName());
                if (isExpired) {
                    holds.delete(h);
                    expired++;
                } else {
                    attempted(h, now);
                    stillHeld++;
                }
                continue;
            }
            ProcessSecretSampleFilter.SampleVerdict verdict = filter.checkTrainingSample(
                    "rejudge_" + h.getSampleKind().name().toLowerCase(), h.getFactoryId(), texts);
            switch (verdict) {
                case CLEAR -> {
                    holds.delete(h);           // 先删 (N4b)
                    try {
                        release(h);
                    } catch (Exception e) {
                        log.warn("挂起训练样本放行时写入失败, 本条不存: id={}, kind={}, factory={}, reason={}",
                                h.getId(), h.getSampleKind(), h.getFactoryId(), e.getClass().getSimpleName());
                    }
                    released++;
                }
                case PROTECTED -> {
                    h.setReviewStatus(TrainingSampleSecrecyHold.ReviewStatus.REJECTED);
                    h.setPayload(TrainingSampleSecrecyHold.DROPPED_PAYLOAD);   // 原文不留 (复审第二轮第 1 条)
                    h.setRejectReason("names_factory_list");
                    h.setRejectedAt(now);
                    h.setLastAttemptAt(now);
                    h.setAttempts(h.getAttempts() + 1);
                    holds.save(h);             // 保留给人工复核, 不删 (Q5)
                    rejected++;
                }
                default -> {
                    if (isExpired) {
                        holds.delete(h);
                        expired++;
                    } else {
                        attempted(h, now);
                        stillHeld++;
                    }
                }
            }
        }
        return new Outcome(released, rejected, stillHeld, expired);
    }

    private void attempted(TrainingSampleSecrecyHold h, LocalDateTime now) {
        h.setAttempts(h.getAttempts() + 1);
        h.setLastAttemptAt(now);
        holds.save(h);
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
