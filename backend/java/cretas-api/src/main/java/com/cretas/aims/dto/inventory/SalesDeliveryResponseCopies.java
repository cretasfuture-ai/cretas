package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.dto.common.PageResponse;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached response copies for delivery records and their price-bearing items. */
public final class SalesDeliveryResponseCopies {

    private SalesDeliveryResponseCopies() {
    }

    public static SalesDeliveryRecord record(SalesDeliveryRecord source) {
        if (source == null) {
            return null;
        }
        SalesDeliveryRecord copy = new SalesDeliveryRecord();
        BeanUtils.copyProperties(source, copy,
                "factory", "salesOrder", "customer", "shippedByUser", "items", "aiInvocationMetadata");
        copy.setItems(items(source.getItems()));
        copy.setAiInvocationMetadata(copyMap(source.getAiInvocationMetadata()));
        return copy;
    }

    public static List<SalesDeliveryRecord> records(List<SalesDeliveryRecord> source) {
        if (source == null) {
            return null;
        }
        return source.stream().map(SalesDeliveryResponseCopies::record).toList();
    }

    public static PageResponse<SalesDeliveryRecord> page(PageResponse<SalesDeliveryRecord> source) {
        if (source == null) {
            return null;
        }
        PageResponse<SalesDeliveryRecord> copy = new PageResponse<>();
        copy.setContent(records(source.getContent()));
        copy.setPage(source.getPage());
        copy.setCurrentPage(source.getCurrentPage());
        copy.setSize(source.getSize());
        copy.setTotalElements(source.getTotalElements());
        copy.setTotalPages(source.getTotalPages());
        copy.setFirst(source.getFirst());
        copy.setLast(source.getLast());
        return copy;
    }

    private static List<SalesDeliveryItem> items(List<SalesDeliveryItem> source) {
        if (source == null) {
            return null;
        }
        return source.stream().map(SalesDeliveryResponseCopies::item).toList();
    }

    private static SalesDeliveryItem item(SalesDeliveryItem source) {
        if (source == null) {
            return null;
        }
        SalesDeliveryItem copy = new SalesDeliveryItem();
        BeanUtils.copyProperties(source, copy, "deliveryRecord", "productType", "finishedGoodsBatch");
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        return (Map<String, Object>) copyJson(source);
    }

    private static Object copyJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), copyJson(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object entry : list) {
                copy.add(copyJson(entry));
            }
            return copy;
        }
        return value;
    }
}
