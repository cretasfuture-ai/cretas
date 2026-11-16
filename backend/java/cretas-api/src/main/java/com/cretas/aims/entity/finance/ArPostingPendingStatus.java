package com.cretas.aims.entity.finance;

/** 待补账记录状态。RESOLVED 由后续「补账」动作置位，本期只预留状态，不提供补账入口。 */
public enum ArPostingPendingStatus {
    PENDING,
    RESOLVED
}
