package com.cretas.aims.service.production;

import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskMaterialBatchPickerServiceContractTest {
    @Mock WorkProcessTaskRepository tasks;
    @Mock ProductionBatchRepository batches;
    @Mock MaterialBatchRepository materials;
    @Mock ProductWorkProcessAssigneeRepository assignees;
    @Mock WorkflowTaskPortRepository ports;
    @Mock WarehouseResolver warehouse;

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void listUsesRealTaskInputPortAndFiltersCandidateShape() {
        actor(1686L, "F006", "operator");
        WorkProcessTask task = task(1989L, 10944L, "F006");
        MaterialBatch good = batch("MB-1", "F006", "RMT-1", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "2");
        MaterialBatch legacyOwnership = batch("MB-2", "F006", "RMT-1", "WH-WKS", null, "PURCHASE_RECEIVE", "1");
        MaterialBatch wrongSku = batch("MB-3", "F006", "RMT-2", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "1");
        MaterialBatch wrongWarehouse = batch("MB-4", "F006", "RMT-1", "WH-LOG", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "1");
        MaterialBatch customerOwned = batch("MB-5", "F006", "RMT-1", "WH-WKS", InventoryOwnership.CUSTOMER_OWNED, "PURCHASE_RECEIVE", "1");
        MaterialBatch wip = batch("MB-6", "F006", "RMT-1", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PRODUCTION_BATCH", "1");
        MaterialBatch empty = batch("MB-7", "F006", "RMT-1", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "0");
        prepareList(task, List.of(port(1989L, "RMT-1", "kg")), List.of(good, legacyOwnership, wrongSku, wrongWarehouse, customerOwned, wip, empty));

        var result = service().list("F006", 10944L, 1989L);

        assertEquals(List.of("MB-1", "MB-2"), result.stream().map(x -> x.getId()).toList());
        verify(materials).findAvailableBatchesFEFOByWarehouse("F006", "RMT-1", "WH-WKS");
    }

    @Test void otherFactoryRequestIsRejected() {
        actor(1686L, "F007", "operator");
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(task(1989L, 10944L, "F006")));
        assertThrows(BusinessException.class, () -> service().list("F006", 10944L, 1989L));
        verifyNoInteractions(batches, materials, ports);
    }

    @Test void unauthenticatedRequestIsRejected() {
        request(null, "F006", "operator");
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(task(1989L, 10944L, "F006")));
        assertThrows(BusinessException.class, () -> service().list("F006", 10944L, 1989L));
    }

    @Test void otherAssignedOperatorIsRejected() {
        actor(1686L, "F006", "operator");
        WorkProcessTask assignedTask = task(1989L, 10944L, "F006");
        assignedTask.setAssignedTo(2000L);
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(assignedTask));
        assertThrows(BusinessException.class, () -> service().list("F006", 10944L, 1989L));
        verifyNoInteractions(batches, materials, ports);
    }

    @Test void mismatchedBatchIsRejected() {
        actor(1686L, "F006", "operator");
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(task(1989L, 9999L, "F006")));
        assertThrows(BusinessException.class, () -> service().list("F006", 10944L, 1989L));
    }

    @Test void missingPortsAndCrossTaskPortsFailClosed() {
        actor(1686L, "F006", "operator");
        WorkProcessTask task = task(1989L, 10944L, "F006");
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(task));
        when(batches.findByIdAndFactoryId(10944L, "F006")).thenReturn(Optional.of(mock(ProductionBatch.class)));
        when(ports.findByFactoryIdAndWorkflowInstanceId("F006", 77L)).thenReturn(List.of(port(1990L, "RMT-2", "kg")));
        assertThrows(BusinessException.class, () -> service().list("F006", 10944L, 1989L));
        verifyNoInteractions(materials);
    }

    @Test void validateAcceptsLegalDuplicateRefsAndRejectsSummedOverage() {
        actor(1686L, "F006", "operator");
        WorkProcessTask task = task(1989L, 10944L, "F006");
        MaterialBatch good = batch("MB-1", "F006", "RMT-1", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "2");
        prepareValidation(task, good, List.of(port(1989L, "RMT-1", "kg")));
        assertDoesNotThrow(() -> service().validateMaterialBatchRefs("F006", 10944L, 1989L,
                List.of(ref("MB-1", "0.5", "kg"), ref("MB-1", "1.5", "kg"))));
        assertThrows(BusinessException.class, () -> service().validateMaterialBatchRefs("F006", 10944L, 1989L,
                List.of(ref("MB-1", "1.1", "kg"), ref("MB-1", "1.0", "kg"))));
    }

    @Test void validateRejectsZeroWrongUnitWrongWarehouseOwnershipAndWip() {
        actor(1686L, "F006", "operator");
        WorkProcessTask task = task(1989L, 10944L, "F006");
        MaterialBatch good = batch("MB-1", "F006", "RMT-1", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "2");
        prepareValidation(task, good, List.of(port(1989L, "RMT-1", "kg")));
        assertThrows(BusinessException.class, () -> service().validateMaterialBatchRefs("F006", 10944L, 1989L, List.of(ref("MB-1", "0", "kg"))));
        assertThrows(BusinessException.class, () -> service().validateMaterialBatchRefs("F006", 10944L, 1989L, List.of(ref("MB-1", "1", "件"))));
        for (MaterialBatch invalid : List.of(
                batch("MB-2", "F006", "RMT-1", "WH-LOG", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "1"),
                batch("MB-3", "F006", "RMT-1", "WH-WKS", InventoryOwnership.CUSTOMER_OWNED, "PURCHASE_RECEIVE", "1"),
                batch("MB-4", "F006", "RMT-1", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PRODUCTION_BATCH", "1"))) {
            when(materials.findByIdAndFactoryId(invalid.getId(), "F006")).thenReturn(Optional.of(invalid));
            assertThrows(BusinessException.class, () -> service().validateMaterialBatchRefs("F006", 10944L, 1989L, List.of(ref(invalid.getId(), "1", "kg"))));
        }
    }

    @Test void validateRejectsWrongSkuAndConflictingPortUnits() {
        actor(1686L, "F006", "operator");
        WorkProcessTask task = task(1989L, 10944L, "F006");
        MaterialBatch wrongSku = batch("MB-1", "F006", "RMT-2", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "1");
        prepareValidation(task, wrongSku, List.of(port(1989L, "RMT-1", "kg")));
        assertThrows(BusinessException.class, () -> service().validateMaterialBatchRefs("F006", 10944L, 1989L, List.of(ref("MB-1", "1", "kg"))));
        when(ports.findByFactoryIdAndWorkflowInstanceId("F006", 77L)).thenReturn(List.of(port(1989L, "RMT-1", "kg"), port(1989L, "RMT-1", "件")));
        assertThrows(BusinessException.class, () -> service().validateMaterialBatchRefs("F006", 10944L, 1989L, List.of(ref("MB-1", "1", "kg"))));
    }

    @Test void missingUnitSnapshotFailsWithBusinessErrorBeforeInventoryRead() {
        actor(1686L, "F006", "operator");
        WorkProcessTask task = task(1989L, 10944L, "F006");
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(task));
        when(batches.findByIdAndFactoryId(10944L, "F006")).thenReturn(Optional.of(mock(ProductionBatch.class)));
        when(ports.findByFactoryIdAndWorkflowInstanceId("F006", 77L)).thenReturn(List.of(port(1989L, "RMT-1", null)));
        assertThrows(BusinessException.class, () -> service().list("F006", 10944L, 1989L));
        verifyNoInteractions(materials, warehouse);
    }

    @Test void validatesScientificStockUnitsAndFrozenPortAliasesWithoutInventedPackaging() {
        actor(1686L, "F006", "operator");
        WorkProcessTask task = task(1989L, 10944L, "F006");
        MaterialBatch stock = batch("MB-1", "F006", "RMT-1", "WH-WKS", InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE", "1000");
        stock.setQuantityUnit("g");
        WorkflowTaskPort input = port(1989L, "RMT-1", "投料千克"); input.setUnitCode("kg");
        WorkflowTaskPort alias = port(1989L, "RMT-1", "原料公斤"); alias.setUnitCode("kg");
        prepareValidation(task, stock, List.of(input, alias));
        assertDoesNotThrow(() -> service().validateMaterialBatchRefs("F006", 10944L, 1989L,
                List.of(ref("MB-1", "1000", "g"))));
        assertThrows(BusinessException.class, () -> service().validateMaterialBatchRefs("F006", 10944L, 1989L,
                List.of(ref("MB-1", "1", "box"))));
        stock.setQuantityUnit("原料公斤");
        assertDoesNotThrow(() -> service().validateMaterialBatchRefs("F006", 10944L, 1989L,
                List.of(ref("MB-1", "1", "原料公斤"))));
    }

    private TaskMaterialBatchPickerService service() { return new TaskMaterialBatchPickerService(tasks, batches, materials, assignees, ports, warehouse); }

    private void prepareList(WorkProcessTask task, List<WorkflowTaskPort> taskPorts, List<MaterialBatch> returned) {
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(task));
        when(batches.findByIdAndFactoryId(10944L, "F006")).thenReturn(Optional.of(mock(ProductionBatch.class)));
        when(ports.findByFactoryIdAndWorkflowInstanceId("F006", 77L)).thenReturn(taskPorts);
        when(warehouse.resolveWorkshopId("F006")).thenReturn("WH-WKS");
        when(materials.findAvailableBatchesFEFOByWarehouse("F006", "RMT-1", "WH-WKS")).thenReturn(returned);
    }

    private void prepareValidation(WorkProcessTask task, MaterialBatch batch, List<WorkflowTaskPort> taskPorts) {
        when(tasks.findByFactoryIdAndId("F006", 1989L)).thenReturn(Optional.of(task));
        when(ports.findByFactoryIdAndWorkflowInstanceId("F006", 77L)).thenReturn(taskPorts);
        when(warehouse.resolveWorkshopId("F006")).thenReturn("WH-WKS");
        when(materials.findByIdAndFactoryId(batch.getId(), "F006")).thenReturn(Optional.of(batch));
    }

    private static WorkProcessTask task(Long id, Long batchId, String factory) {
        return WorkProcessTask.builder().id(id).factoryId(factory).productionBatchId(batchId).productTypeId("PT-1").workflowInstanceId(77L).assignedTo(1686L).build();
    }

    private static MaterialBatch batch(String id, String factory, String sku, String warehouseId, InventoryOwnership ownership, String source, String receipt) {
        MaterialBatch b = new MaterialBatch(); b.setId(id); b.setFactoryId(factory); b.setBatchNumber("B-" + id); b.setMaterialTypeId(sku); b.setWarehouseId(warehouseId); b.setOwnership(ownership); b.setSourceDocType(source); b.setStatus(MaterialBatchStatus.AVAILABLE); b.setReceiptQuantity(new BigDecimal(receipt)); b.setUsedQuantity(BigDecimal.ZERO); b.setReservedQuantity(BigDecimal.ZERO); b.setQuantityUnit("kg"); return b;
    }

    private static WorkflowTaskPort port(Long taskId, String skuId, String unit) {
        WorkflowTaskPort p = new WorkflowTaskPort(); p.setTaskId(taskId); p.setDirection(WorkflowTaskPort.Direction.INPUT); p.setMaterialKind("RAW_MATERIAL"); p.setSkuId(skuId); p.setUnit(unit); return p;
    }

    private static MaterialBatchRef ref(String id, String quantity, String unit) { return new MaterialBatchRef(id, new BigDecimal(quantity), unit); }
    private static void actor(Long userId, String factory, String role) { request(userId, factory, role); }
    private static void request(Long userId, String factory, String role) { MockHttpServletRequest r = new MockHttpServletRequest(); r.setAttribute("userId", userId); r.setAttribute("factoryId", factory); r.setAttribute("role", role); RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r)); }
}
