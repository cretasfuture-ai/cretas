package com.cretas.aims.service;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 出货记录只存 customer_id; 列表接口早就补了 customerName, 但详情 / 按客户 / 按日期范围没补,
 * App 的发货详情屏只能拿 id 兜底显示成 UUID。本测试钉死: 所有返回出货记录的读方法都补客户名,
 * 查不到客户时 customerName 留空(不回落成 id)。
 */
class ShipmentCustomerNameContractTest {

    private final ShipmentRecordRepository shipmentRepo = mock(ShipmentRecordRepository.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final ShipmentRecordService service = new ShipmentRecordService(shipmentRepo, customerRepo);

    private static ShipmentRecord shipment(String id, String customerId) {
        ShipmentRecord r = new ShipmentRecord();
        r.setId(id);
        r.setCustomerId(customerId);
        return r;
    }

    private void customerExists(String id, String name) {
        Customer c = new Customer();
        c.setId(id);
        c.setName(name);
        when(customerRepo.findAllById(any())).thenReturn(List.of(c));
    }

    @Test
    void getByIdFillsCustomerName() {
        when(shipmentRepo.findById("s1")).thenReturn(Optional.of(shipment("s1", "c1")));
        customerExists("c1", "六膳门");
        assertThat(service.getById("s1")).get().extracting(ShipmentRecord::getCustomerName).isEqualTo("六膳门");
    }

    @Test
    void getByIdAndFactoryIdFillsCustomerName() {
        when(shipmentRepo.findByIdAndFactoryId("s1", "F006")).thenReturn(Optional.of(shipment("s1", "c1")));
        customerExists("c1", "测试客户");
        assertThat(service.getByIdAndFactoryId("s1", "F006")).get()
                .extracting(ShipmentRecord::getCustomerName).isEqualTo("测试客户");
    }

    @Test
    void getByCustomerFillsCustomerName() {
        when(shipmentRepo.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(anyString(), anyString()))
                .thenReturn(List.of(shipment("s1", "c1")));
        customerExists("c1", "测试客户");
        assertThat(service.getByCustomer("F006", "c1")).extracting(ShipmentRecord::getCustomerName)
                .containsExactly("测试客户");
    }

    @Test
    void unknownCustomerStaysNullNotId() {
        when(shipmentRepo.findById("s1")).thenReturn(Optional.of(shipment("s1", "c-gone")));
        when(customerRepo.findAllById(any())).thenReturn(List.of());
        ShipmentRecord r = service.getById("s1").orElseThrow();
        assertThat(r.getCustomerName()).isNull();
    }

    @Test
    void missingShipmentDoesNotQueryCustomers() {
        when(shipmentRepo.findById("none")).thenReturn(Optional.empty());
        assertThat(service.getById("none")).isEmpty();
        Mockito.verifyNoInteractions(customerRepo);
    }
}
