package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CustomerMaterialArrivalReceiptRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.CustomerMaterialArrivalNotice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.CustomerMaterialArrivalNoticeRepository;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迁移 P5 R35 在真实入口 (无订单入库实收 {@link CustomerMaterialArrivalNoticeService#receive}) 上:
 * 到期日早于今天 ⇒ 不写原因 409 且零库存写; 写了原因 ⇒ 建批次, 原因记在批次上; 没过期 ⇒ 不要原因、不存原因。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpiredBatchReasonOnReceiveContractTest {

    @Mock private CustomerMaterialArrivalNoticeRepository noticeRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private FactoryWarehouseRepository warehouseRepository;
    @Mock private WarehouseInventoryGuardService warehouseInventoryGuardService;
    @Mock private UnitContractService unitContractService;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;

    private CustomerMaterialArrivalNoticeService service;

    @BeforeEach
    void setUp() {
        service = new CustomerMaterialArrivalNoticeService(noticeRepository, customerRepository,
                rawMaterialTypeRepository, warehouseRepository, warehouseInventoryGuardService,
                unitContractService, materialBatchRepository, materialBatchMapper);
        CustomerMaterialArrivalNotice notice = new CustomerMaterialArrivalNotice();
        notice.setId("NOTICE-1");
        notice.setFactoryId("F006");
        notice.setCustomerId("CUSTOMER-1");
        notice.setStatus(CustomerMaterialArrivalStatus.OPEN);
        notice.setReceiptCount(0);
        when(noticeRepository.findByIdAndFactoryIdForUpdate("NOTICE-1", "F006")).thenReturn(Optional.of(notice));
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        RawMaterialType material = new RawMaterialType();
        material.setId("RM-1");
        material.setFactoryId("F006");
        material.setIsActive(true);
        material.setUnit("kg");
        material.setCategory("原料");
        when(rawMaterialTypeRepository.findById("RM-1")).thenReturn(Optional.of(material));
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId("WH-RAW");
        warehouse.setFactoryId("F006");
        warehouse.setIsActive(true);
        when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("WH-RAW", "F006")).thenReturn(Optional.of(warehouse));
        CanonicalUnit kg = new CanonicalUnit("kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 4,
                Set.of(UnitUsageScope.INVENTORY_QUANTITY), "kg", true);
        when(unitContractService.normalize("F006", "kg")).thenReturn(new UnitNormalizationResult("kg", "kg", kg));
        when(unitContractService.supportsUsage("F006", "kg", UnitUsageScope.INVENTORY_QUANTITY)).thenReturn(true);
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(noticeRepository.save(any(CustomerMaterialArrivalNotice.class))).thenAnswer(i -> i.getArgument(0));
        when(materialBatchMapper.toDTO(any(MaterialBatch.class))).thenReturn(new MaterialBatchDTO());
    }

    private static CustomerMaterialArrivalReceiptRequest request(LocalDate expire, String reason) {
        CustomerMaterialArrivalReceiptRequest r = new CustomerMaterialArrivalReceiptRequest();
        r.setIdempotencyKey("k-" + expire + "-" + reason);
        r.setMaterialTypeId("RM-1");
        r.setWarehouseId("WH-RAW");
        r.setReceivedQuantity(new BigDecimal("10"));
        r.setUnit("kg");
        r.setExpireDate(expire);
        r.setExpiredReason(reason);
        return r;
    }

    @Test
    @DisplayName("阴性: 到期日已过、没写原因 ⇒ 409 EXPIRED_BATCH_REASON_REQUIRED, 措辞写明过期天数, 零库存写")
    void expiredWithoutReasonIsRejectedBeforeAnyWrite() {
        LocalDate expired = LocalDate.now().minusDays(3);
        assertThatThrownBy(() -> service.receive("F006", "NOTICE-1", request(expired, "  "), 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("EXPIRED_BATCH_REASON_REQUIRED");
                    assertThat(e.getMessage()).contains("已过期 3 天");
                    assertThat(e.getActionHint()).isNotBlank();
                });
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("阳性: 到期日已过、写了原因 ⇒ 建批次, 原因 (去首尾空白) 记在批次上")
    void expiredWithReasonIsSavedOnTheBatch() {
        LocalDate expired = LocalDate.now().minusDays(1);
        service.receive("F006", "NOTICE-1", request(expired, "  客户坚持送来，退货前暂存  "), 7L);
        ArgumentCaptor<MaterialBatch> c = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).saveAndFlush(c.capture());
        assertThat(c.getValue().getExpiredOnCreateReason()).isEqualTo("客户坚持送来，退货前暂存");
        assertThat(c.getValue().getExpireDate()).isEqualTo(expired);
    }

    @Test
    @DisplayName("没过期 (今天到期也不算过期) ⇒ 不要求原因; 顺手传了原因也不存")
    void notExpiredNeedsNoReasonAndStoresNone() {
        service.receive("F006", "NOTICE-1", request(LocalDate.now(), "多传的"), 7L);
        ArgumentCaptor<MaterialBatch> c = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).saveAndFlush(c.capture());
        assertThat(c.getValue().getExpiredOnCreateReason()).isNull();
    }
}
