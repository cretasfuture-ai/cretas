package com.cretas.aims.entity.processsecret;

/** 签名只证明来源与完整性, 不证明生产事实, 所以结果落库时一律待核验。 */
public enum ProcessSecretResultReviewStatus {
    PENDING_REVIEW,
    ACCEPTED,
    REJECTED
}
