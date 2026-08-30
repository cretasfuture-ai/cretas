package com.cretas.aims.dto.production;

import lombok.Data;

import java.util.List;

/** One-shot, explicitly scoped repair of historical WIP OUT ledger rows. */
@Data
public class WipOutHistoryRepairRequestDTO {
    private Long reversalLogId;
    private List<Long> originalOutTxnIds;
}
