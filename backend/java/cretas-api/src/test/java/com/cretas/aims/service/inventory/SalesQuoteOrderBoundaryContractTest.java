package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.sales.OperationalQuoteOrderGuard;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SalesQuoteOrderBoundaryContractTest {
    final SalesOrderRepository orders=mock(SalesOrderRepository.class);
    final SalesOrderItemRepository items=mock(SalesOrderItemRepository.class);
    final OperationalQuoteOrderGuard guard=mock(OperationalQuoteOrderGuard.class);
    final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    final CustomerRepository customers=mock(CustomerRepository.class);
    final ProductTypeRepository products=mock(ProductTypeRepository.class);
    final SalesServiceImpl service=new SalesServiceImpl(orders,items,mock(SalesDeliveryRecordRepository.class),
            mock(FinishedGoodsBatchRepository.class),customers,products,mock(ArApService.class),events);
    @Test void createRejectsTheReferenceBeforeSavingAnyOrderOrItem() {
        ReflectionTestUtils.setField(service,"operationalQuoteOrderGuard",guard);
        var request=new CreateSalesOrderRequest();request.setQuoteId("Q");
        doThrow(new BusinessException(409,"报价未批准")).when(guard).validateCreate("F",request);
        assertThrows(BusinessException.class,()->service.createSalesOrder("F",request,1L));
        verify(guard).validateCreate("F",request);verifyNoInteractions(orders,items,events);
    }
    @Test void confirmationRejectsStaleReferenceBeforeStatusSaveOrEvent() {
        ReflectionTestUtils.setField(service,"operationalQuoteOrderGuard",guard);
        var order=new SalesOrder();order.setId("O");order.setFactoryId("F");order.setQuoteId("Q");order.setStatus(SalesOrderStatus.DRAFT);
        when(orders.findByIdAndFactoryIdForUpdate("O","F")).thenReturn(Optional.of(order));
        when(items.findBySalesOrderId("O")).thenReturn(List.of());
        doThrow(new BusinessException(409,"报价已修订")).when(guard).validateConfirmation(eq("F"),eq(order),anyList());
        assertThrows(BusinessException.class,()->service.confirmOrder("F","O",1L));
        verify(guard).validateConfirmation("F",order,List.of());
        assertEquals(SalesOrderStatus.DRAFT,order.getStatus());verify(orders,never()).save(any());verifyNoInteractions(events);
    }
    @Test void approvedGrossSnapshotIsSavedAndAutomaticPriceStrategiesCannotRewriteIt() {
        prepareCreate();
        var pricing=mock(com.cretas.aims.service.pricing.PricingEngine.class);
        ReflectionTestUtils.setField(service,"pricingEngine",pricing);
        var created=service.createSalesOrder("F",request(),1L);
        assertEquals(new BigDecimal("124.00"),created.getPayableAmount());
        assertEquals(new BigDecimal("14.27"),created.getTaxAmount());
        verifyNoInteractions(pricing);
        verify(guard).validateConfirmation(eq("F"),eq(created),argThat(lines -> lines.size()==1
                && lines.get(0).getQuotedGrossUnitPrice().compareTo(new BigDecimal("62"))==0
                && lines.get(0).getUnitPrice().scale()==4));
    }
    @Test void actualEntityValidationPrecedesAnyItemSaveOrCreateEvent() {
        prepareCreate();
        doThrow(new BusinessException(409,"策略后价格不匹配")).when(guard).validateConfirmation(eq("F"),any(),anyList());
        assertThrows(BusinessException.class,()->service.createSalesOrder("F",request(),1L));
        verify(items,never()).saveAll(any());verifyNoInteractions(events);
    }
    @Test void copyRevalidatesAndPreservesTheSnapshotAndTaxAmount() {
        prepareCreate();var source=source();when(orders.findById("SOURCE")).thenReturn(Optional.of(source));
        var copied=service.copySalesOrder("F","SOURCE",1L);
        verify(guard).validateConfirmation("F",source,source.getItems());
        assertEquals(new BigDecimal("124.00"),copied.getPayableAmount());
        verify(items).saveAll(argThat(lines -> ((List<com.cretas.aims.entity.inventory.SalesOrderItem>)lines).get(0)
                .getQuotedGrossUnitPrice().compareTo(new BigDecimal("62"))==0));
    }
    @Test void staleQuoteCopyRejectsBeforeCreatingAnotherDraft() {
        prepareCreate();var source=source();when(orders.findById("SOURCE")).thenReturn(Optional.of(source));
        doThrow(new BusinessException(409,"报价已修订")).when(guard).validateConfirmation("F",source,source.getItems());
        assertThrows(BusinessException.class,()->service.copySalesOrder("F","SOURCE",1L));
        verify(orders,never()).save(any());verify(items,never()).saveAll(any());
    }
    @Test void fullDraftEditRecalculatesTheHeaderWithTheAuthoritativeSnapshot() {
        prepareCreate();var source=source();when(orders.findById("SOURCE")).thenReturn(Optional.of(source));
        when(items.findBySalesOrderId("SOURCE")).thenReturn(source.getItems());
        var update=new com.cretas.aims.dto.inventory.UpdateSalesOrderRequest();update.setItems(request().getItems());
        update.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        update.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        when(guard.validateUpdate(eq("F"),eq(source),eq(update),anyList())).thenReturn(new BigDecimal("62"));
        var updated=service.updateSalesOrder("F","SOURCE",update);
        assertEquals(new BigDecimal("124.00"),updated.getPayableAmount());
        verify(guard).validateConfirmation(eq("F"),eq(source),argThat(lines -> lines.get(0).getQuotedGrossUnitPrice()!=null));
    }
    private void prepareCreate() {
        ReflectionTestUtils.setField(service,"operationalQuoteOrderGuard",guard);
        when(guard.validateCreate(eq("F"),any())).thenReturn(new BigDecimal("62"));
        var em=mock(jakarta.persistence.EntityManager.class);var query=mock(jakarta.persistence.Query.class);
        ReflectionTestUtils.setField(service,"entityManager",em);
        ReflectionTestUtils.setField(service,"warehouseResolver",mock(com.cretas.aims.service.factory.WarehouseResolver.class));
        when(em.createNativeQuery(anyString())).thenReturn(query);when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        var customer=new com.cretas.aims.entity.Customer();customer.setId("C");customer.setFactoryId("F");customer.setName("验收客户");
        when(customers.findByIdAndFactoryId("C","F")).thenReturn(Optional.of(customer));
        var product=new com.cretas.aims.entity.ProductType();product.setId("P");product.setFactoryId("F");product.setName("验收成品");
        product.setProductCategory("FINISHED_PRODUCT");product.setUnit("个");product.setIsActive(true);product.setCreatedBy(1L);
        when(products.findByIdAndFactoryId("P","F")).thenReturn(Optional.of(product));when(products.findById("P")).thenReturn(Optional.of(product));
        when(orders.save(any())).thenAnswer(i->{SalesOrder o=i.getArgument(0);if(o.getId()==null)o.setId("O");return o;});
        when(items.saveAll(any())).thenAnswer(i->i.getArgument(0));
    }
    private CreateSalesOrderRequest request() {
        var request=new CreateSalesOrderRequest();request.setQuoteId("Q");request.setCustomerId("C");
        request.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        request.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        var line=new CreateSalesOrderRequest.SalesOrderItemDTO();line.setProductTypeId("P");line.setProductName("验收成品");
        line.setQuantity(new BigDecimal("2"));line.setUnit("个");line.setUnitPrice(new BigDecimal("54.86731"));line.setTaxRate(new BigDecimal("13"));
        request.setItems(List.of(line));return request;
    }
    private SalesOrder source() {
        var order=new SalesOrder();order.setId("SOURCE");order.setFactoryId("F");order.setCustomerId("C");order.setQuoteId("Q");order.setStatus(SalesOrderStatus.DRAFT);
        order.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        order.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        var line=new com.cretas.aims.entity.inventory.SalesOrderItem();line.setProductTypeId("P");line.setUnit("个");line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("54.8673"));line.setTaxRate(new BigDecimal("13"));line.setQuotedGrossUnitPrice(new BigDecimal("62"));
        line.setProcessingMode(order.getProcessingMode());line.setMaterialSupplyMode(order.getMaterialSupplyMode());
        order.setItems(new ArrayList<>(List.of(line)));return order;
    }
}
