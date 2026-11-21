package com.cretas.aims.dto.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.service.inventory.TradeCaliberSettings.CustomerAverageMode;
import lombok.Getter;
import org.springframework.beans.BeanUtils;

import java.util.List;

/** The active calculation mode applies to the whole page, including empty results. */
@Getter
public final class CustomerPriceOverviewPage extends PageResponse<CustomerPriceOverviewRow> {
    private final CustomerAverageMode customerTradedAverageMode;

    public CustomerPriceOverviewPage(List<CustomerPriceOverviewRow> rows, int page, int size, long total,
                                     CustomerAverageMode mode) {
        BeanUtils.copyProperties(PageResponse.of(rows, page, size, total), this);
        this.customerTradedAverageMode = mode;
    }
}
