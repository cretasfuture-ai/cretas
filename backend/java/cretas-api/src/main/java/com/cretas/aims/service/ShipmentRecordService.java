package com.cretas.aims.service;

import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.ShipmentRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 出货记录服务层
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class ShipmentRecordService {

    private final ShipmentRecordRepository shipmentRecordRepository;
    private final com.cretas.aims.repository.CustomerRepository customerRepository;

    /**
     * 根据ID获取出货记录
     */
    public Optional<ShipmentRecord> getById(String id) {
        Optional<ShipmentRecord> found = shipmentRecordRepository.findById(id);
        found.ifPresent(r -> fillCustomerNames(java.util.List.of(r)));
        return found;
    }

    /**
     * 根据出货单号获取
     */
    public Optional<ShipmentRecord> getByShipmentNumber(String shipmentNumber) {
        return shipmentRecordRepository.findByShipmentNumber(shipmentNumber);
    }

    /**
     * 分页查询工厂出货记录
     */
    public Page<ShipmentRecord> getByFactoryId(String factoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ShipmentRecord> result = shipmentRecordRepository
                .findByFactoryIdOrderByShipmentDateDesc(factoryId, pageable);
        fillCustomerNames(result.getContent());
        return result;
    }

    /**
     * 按状态分页查询
     */
    /**
     * 按客户 / 状态 / 出货日期筛选 (每个可选, null = 不限), 在分页之前由数据库过滤; page 从 0 开始。
     */
    public Page<ShipmentRecord> searchFiltered(String factoryId, String customerId, String status,
                                               LocalDate shipmentDateFrom, LocalDate shipmentDateTo,
                                               int page, int size) {
        if (shipmentDateFrom != null && shipmentDateTo != null && shipmentDateFrom.isAfter(shipmentDateTo)) {
            throw new com.cretas.aims.exception.BusinessException(400,
                    "出货日期范围不成立: 开始 " + shipmentDateFrom + " 晚于结束 " + shipmentDateTo)
                    .withHint("请把开始日期改成不晚于结束日期");
        }
        Page<ShipmentRecord> result = shipmentRecordRepository.searchFiltered(factoryId, customerId, status,
                shipmentDateFrom, shipmentDateTo, PageRequest.of(page, size));
        fillCustomerNames(result.getContent());
        return result;
    }

    public Page<ShipmentRecord> getByFactoryIdAndStatus(String factoryId, String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ShipmentRecord> result = shipmentRecordRepository
                .findByFactoryIdAndStatusOrderByShipmentDateDesc(factoryId, status, pageable);
        fillCustomerNames(result.getContent());
        return result;
    }

    /**
     * 批量补客户名 —— 出货记录只存 customer_id，界面上直接显示就是一串 UUID。
     *
     * ⚠️ 一次 findAllById，⛔ 不逐行查(那是 1+N)。
     * ⚠️ 查不到就**留空**，⛔ 不回落成 id —— 回落等于把「查不到」伪装成一个像模像样的答案，
     *    调用方分不出「这个客户就叫这串字符」和「我没查到」。前端自己决定怎么显示。
     */
    private void fillCustomerNames(java.util.List<ShipmentRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        java.util.Set<String> ids = records.stream()
                .map(ShipmentRecord::getCustomerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<String, String> nameById = new java.util.HashMap<>();
        customerRepository.findAllById(ids)
                .forEach(c -> nameById.put(c.getId(), c.getName()));
        records.forEach(r -> r.setCustomerName(nameById.get(r.getCustomerId())));
    }


    /**
     * 按客户查询
     */
    public List<ShipmentRecord> getByCustomer(String factoryId, String customerId) {
        List<ShipmentRecord> result = shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(factoryId, customerId);
        fillCustomerNames(result);
        return result;
    }

    /**
     * 按日期范围查询
     */
    public List<ShipmentRecord> getByDateRange(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<ShipmentRecord> result = shipmentRecordRepository.findByFactoryIdAndDateRange(factoryId, startDate, endDate);
        fillCustomerNames(result);
        return result;
    }

    /**
     * 根据物流单号查询
     */
    public Optional<ShipmentRecord> getByTrackingNumber(String trackingNumber) {
        return shipmentRecordRepository.findByTrackingNumber(trackingNumber);
    }

    /**
     * 统计出货数量
     */
    public long countByFactoryId(String factoryId) {
        return shipmentRecordRepository.countByFactoryId(factoryId);
    }

    /**
     * 统计指定状态的出货数量
     */
    public long countByStatus(String factoryId, String status) {
        return shipmentRecordRepository.countByFactoryIdAndStatus(factoryId, status);
    }

    /**
     * 根据ID和工厂ID获取出货记录（工厂隔离）
     */
    public Optional<ShipmentRecord> getByIdAndFactoryId(String id, String factoryId) {
        Optional<ShipmentRecord> found = shipmentRecordRepository.findByIdAndFactoryId(id, factoryId);
        found.ifPresent(r -> fillCustomerNames(java.util.List.of(r)));
        return found;
    }

    /**
     * 根据出货单号和工厂ID获取（工厂隔离）
     */
    public Optional<ShipmentRecord> getByShipmentNumberAndFactoryId(String shipmentNumber, String factoryId) {
        return shipmentRecordRepository.findByShipmentNumberAndFactoryId(shipmentNumber, factoryId);
    }

    /**
     * 根据物流单号和工厂ID获取（工厂隔离）
     */
    public Optional<ShipmentRecord> getByTrackingNumberAndFactoryId(String trackingNumber, String factoryId) {
        return shipmentRecordRepository.findByTrackingNumberAndFactoryId(trackingNumber, factoryId);
    }
}
