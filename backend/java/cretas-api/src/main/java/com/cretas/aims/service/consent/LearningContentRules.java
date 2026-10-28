package com.cretas.aims.service.consent;

import com.cretas.aims.entity.learning.LearnedExpression;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统「学」用户原话 (说法、关键词、短语映射、建议) 之前的统一判断 —— Steve 2026-10-29:
 * 工艺保密产品可以出现在授权用户的对话里, 但不许被学走、在别的工厂复现。
 *
 * <ul>
 *   <li>{@link #factoryLearnable}: 只写进<b>本厂</b>的东西。与训练样本 ({@code recordSample}) 同一套规则:
 *       撤回 / 查不到授权状态不学; 提到本厂工艺保密产品不学; 配方 / 工艺参数、具体客户名、人名不学。</li>
 *   <li>{@link #isGlobalFactory}: 工厂字段为空、空白或 "*" 的行对所有工厂可见。</li>
 *   <li>{@link #servable}: 读出口 —— 系统学来的全局行 (不是人工预置 MANUAL) 来自某一家工厂, 不给任何工厂用。</li>
 * </ul>
 * 判断所需的服务缺失或查询出错, 一律按「不能学」处理 (fail-closed)。
 */
@Slf4j
public final class LearningContentRules {

    private LearningContentRules() {
    }

    /** 全局行: 工厂字段为空、空白或 "*" (种子用 "*", 历史写入有空)。 */
    public static boolean isGlobalFactory(String factoryId) {
        return factoryId == null || factoryId.isBlank() || "*".equals(factoryId.trim());
    }

    /** 这段原话能不能学进本厂 (全局一律不行, 全局写入走人工预置 + 全厂保密名单检查)。 */
    public static boolean factoryLearnable(TrainingConsentService consent, ProcessSecretSampleFilter filter,
                                           String factoryId, String text) {
        if (isGlobalFactory(factoryId) || text == null || text.isBlank()) {
            return false;
        }
        if (consent == null || filter == null) {
            log.warn("训练授权 / 保密产品判断未注入, 不学: factory={}", factoryId);
            return false;
        }
        try {
            if (!consent.status(factoryId).storable()) {
                return false;
            }
            if (filter.mentionsProtectedProduct(factoryId, text)) {
                log.info("原话提到本厂工艺保密产品, 不学: factory={}", factoryId);
                return false;
            }
            return !TrainingSampleRules.utteranceHasBlockedContent(text);
        } catch (RuntimeException e) {
            log.warn("学习前的保密 / 授权判断失败, 不学: factory={}, error={}", factoryId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** 系统学来的全局行 (全局且不是 MANUAL): 不给任何工厂用。 */
    public static boolean systemLearnedGlobal(LearnedExpression e) {
        return e != null && isGlobalFactory(e.getFactoryId())
                && e.getSourceType() != LearnedExpression.SourceType.MANUAL;
    }

    /** 读出口能不能把这一行给调用方 (本厂行、或人工预置的全局行)。 */
    public static boolean servable(LearnedExpression e) {
        return e != null && !systemLearnedGlobal(e);
    }
}
