package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CustomerPriceItemView;
import com.cretas.aims.entity.inventory.PriceList;
import com.cretas.aims.entity.inventory.PriceListItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.PriceListItemRepository;
import com.cretas.aims.repository.inventory.PriceListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户专属价目维护 (客户档案「价格」页)。复用 price_lists / price_list_items (SELLING_PRICE + customer_id),
 * 开单取价仍走 {@link PriceListService#resolveForOrder}, 所以这里维护的价就是开单带出的价。
 * 所有查询带 factory_id; 只碰 SELLING_PRICE, 绝不读写采购价目 (PURCHASE_PRICE)。
 */
@Service
@RequiredArgsConstructor
public class CustomerPriceListService {

    public static final String SELLING_PRICE = "SELLING_PRICE";
    public static final String DEDICATED_LIST_NAME = "客户专属价目";

    private final PriceListRepository priceListRepository;
    private final PriceListItemRepository priceListItemRepository;
    private final CustomerRepository customerRepository;
    private final ProductTypeRepository productTypeRepository;

    @Transactional(readOnly = true)
    public List<CustomerPriceItemView> list(String factoryId, String customerId) {
        requireCustomer(factoryId, customerId);
        List<CustomerPriceItemView> rows = new ArrayList<>();
        for (PriceList pl : priceListRepository
                .findByFactoryIdAndCustomerIdAndPriceTypeOrderByCreatedAtAsc(factoryId, customerId, SELLING_PRICE)) {
            for (PriceListItem it : priceListItemRepository.findByPriceListId(pl.getId())) {
                if (it.getProductTypeId() == null) {
                    continue;
                }
                rows.add(new CustomerPriceItemView(it.getId(), pl.getId(), pl.getName(),
                        it.getProductTypeId(), it.getItemName(), it.getUnit(), it.getStandardPrice(),
                        pl.getEffectiveFrom(), pl.getEffectiveTo(), pl.isCurrentlyEffective()));
            }
        }
        return rows;
    }

    /** 该客户该产品已有专属价则改价, 否则新增 (落在「客户专属价目」这张表里, 没有就建)。 */
    @Transactional
    public CustomerPriceItemView upsert(String factoryId, String customerId, Long userId,
                                        String productTypeId, BigDecimal standardPrice) {
        requireCustomer(factoryId, customerId);
        if (standardPrice == null || standardPrice.signum() <= 0) {
            throw new BusinessException("单价必须大于 0");
        }
        var product = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new BusinessException("产品不存在或不属于当前工厂"));

        List<PriceList> lists = priceListRepository
                .findByFactoryIdAndCustomerIdAndPriceTypeOrderByCreatedAtAsc(factoryId, customerId, SELLING_PRICE);
        // 已有该产品的行 → 就地改价 (无论落在哪张价目表)
        for (PriceList pl : lists) {
            for (PriceListItem it : priceListItemRepository.findByPriceListIdAndProductTypeId(pl.getId(), productTypeId)) {
                it.setStandardPrice(standardPrice);
                priceListItemRepository.save(it);
                return view(pl, it);
            }
        }
        PriceList target = lists.stream()
                .filter(l -> DEDICATED_LIST_NAME.equals(l.getName()) && Boolean.TRUE.equals(l.getIsActive()))
                .findFirst().orElse(null);
        if (target == null) {
            target = new PriceList();
            target.setFactoryId(factoryId);
            target.setCustomerId(customerId);
            target.setName(DEDICATED_LIST_NAME);
            target.setPriceType(SELLING_PRICE);
            target.setEffectiveFrom(LocalDate.now());
            target.setCreatedBy(userId);
            target = priceListRepository.save(target);
        }
        PriceListItem item = new PriceListItem();
        item.setPriceListId(target.getId());
        item.setProductTypeId(productTypeId);
        item.setItemName(product.getName());
        item.setUnit(product.getUnit());
        item.setStandardPrice(standardPrice);
        item = priceListItemRepository.save(item);
        return view(target, item);
    }

    @Transactional
    public void delete(String factoryId, String customerId, Long itemId) {
        requireCustomer(factoryId, customerId);
        PriceListItem item = priceListItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("价目行不存在"));
        PriceList pl = priceListRepository.findById(item.getPriceListId())
                .filter(l -> factoryId.equals(l.getFactoryId())
                        && customerId.equals(l.getCustomerId())
                        && SELLING_PRICE.equals(l.getPriceType()))
                .orElseThrow(() -> new ResourceNotFoundException("价目行不存在"));
        priceListItemRepository.delete(item);
    }

    private CustomerPriceItemView view(PriceList pl, PriceListItem it) {
        return new CustomerPriceItemView(it.getId(), pl.getId(), pl.getName(), it.getProductTypeId(),
                it.getItemName(), it.getUnit(), it.getStandardPrice(), pl.getEffectiveFrom(),
                pl.getEffectiveTo(), pl.isCurrentlyEffective());
    }

    private void requireCustomer(String factoryId, String customerId) {
        customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在"));
    }
}
