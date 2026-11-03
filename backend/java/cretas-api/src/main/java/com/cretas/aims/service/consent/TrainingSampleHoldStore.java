package com.cretas.aims.service.consent;

import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import com.cretas.aims.repository.learning.TrainingSampleSecrecyHoldRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 「挂起待重判」的训练样本 (Flyway V20261104_11, 待办第 2 / 9 条) 的写入口。
 *
 * <p>{@link ProcessSecretSampleFilter#checkTrainingSample} 返回 {@code HELD} (本厂名单这次查不出来, 按上次成功的名单
 * 判下来没问题) 时, 收样本的入口把「将要写进样本表的那一条」交到这里; {@link TrainingSampleHoldRejudger}
 * 按当前名单重判后才真正写进样本表。写失败只丢这一条, 不抛 (在意图识别链路上)。
 */
@Component
public class TrainingSampleHoldStore {

    private static final Logger log = LoggerFactory.getLogger(TrainingSampleHoldStore.class);

    /** 意图样本 (ai_training_samples) 的原话原文 (复审 N3: 重判用原文; 放行时由 recordSample 照常脱敏)。 */
    public record IntentSample(String userInput, String intentCode, String matchMethod, double confidence,
                               String sessionId) {
    }

    /** 低置信样本 (active_learning_samples) 的原文 (放行时由 collectSample 照常脱敏)。 */
    public record ActiveLearningSampleData(Long userId, String userInput, String normalizedInput,
                                           String matchedIntentCode, BigDecimal confidenceScore,
                                           String matchMethod, String topCandidates, String matchedKeywords,
                                           String sourceRecordId) {
    }

    static final ObjectMapper JSON = new ObjectMapper();

    private final TrainingSampleSecrecyHoldRepository repository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TrainingSampleHoldStore(TrainingSampleSecrecyHoldRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    TrainingSampleHoldStore(TrainingSampleSecrecyHoldRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public boolean holdIntent(String factoryId, IntentSample sample) {
        return hold(TrainingSampleSecrecyHold.Kind.INTENT, factoryId, sample);
    }

    public boolean holdActiveLearning(String factoryId, ActiveLearningSampleData sample) {
        return hold(TrainingSampleSecrecyHold.Kind.ACTIVE_LEARNING, factoryId, sample);
    }

    private boolean hold(TrainingSampleSecrecyHold.Kind kind, String factoryId, Object sample) {
        try {
            repository.save(TrainingSampleSecrecyHold.of(kind, factoryId, JSON.writeValueAsString(sample),
                    LocalDateTime.now(clock)));
            return true;
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("训练样本挂起失败, 本条不存: kind={}, factory={}, reason={}",
                    kind, factoryId, e.getClass().getSimpleName());
            return false;
        }
    }

    static IntentSample intent(TrainingSampleSecrecyHold h) throws JsonProcessingException {
        return JSON.readValue(h.getPayload(), IntentSample.class);
    }

    static ActiveLearningSampleData activeLearning(TrainingSampleSecrecyHold h) throws JsonProcessingException {
        return JSON.readValue(h.getPayload(), ActiveLearningSampleData.class);
    }
}
