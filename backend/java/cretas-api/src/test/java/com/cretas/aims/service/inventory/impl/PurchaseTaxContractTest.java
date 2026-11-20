package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.material.MaterialReferenceWriteGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PurchaseTaxContractTest {
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository items = mock(PurchaseOrderItemRepository.class);
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final PurchaseServiceImpl service = new PurchaseServiceImpl(orders, items, null, null,
            materials, null, null, null, null, null, mock(MaterialReferenceWriteGuard.class));

    @BeforeEach
    void setUp() {
        RawMaterialType material = new RawMaterialType();
        material.setId("material-1");
        material.setFactoryId("F006");
        material.setName("原料A");
        material.setUnit("kg");
        when(materials.findById(material.getId())).thenReturn(Optional.of(material));
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createRejectsFractionalInputWithBadRequestAndEntryGuidance() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.createPurchaseOrder("F006", draft("0.13"), 9L))
                .satisfies(error -> {
                    var response = new GlobalExceptionHandler().handleBusinessException(error);
                    assertThat(response.getStatusCode().value()).isEqualTo(400);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getMessage()).contains("按百分数填写", "13% 填 13");
                });
        verifyNoInteractions(items);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "13", "100"})
    void createKeepsExplicitPercentValue(String rate) {
        service.createPurchaseOrder("F006", draft(rate), 9L);
        ArgumentCaptor<Iterable<PurchaseOrderItem>> saved = ArgumentCaptor.captor();
        verify(items).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(item ->
                assertThat(item.getTaxRate()).isEqualByComparingTo(rate));
    }

    private static CreatePurchaseOrderRequest draft(String rate) {
        var item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId("material-1");
        item.setMaterialName("原料A");
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("kg");
        item.setUnitPrice(BigDecimal.TEN);
        item.setTaxRate(new BigDecimal(rate));

        var request = new CreatePurchaseOrderRequest();
        request.setPurchaseType("DIRECT");
        request.setItems(List.of(item));
        return request;
    }
}
