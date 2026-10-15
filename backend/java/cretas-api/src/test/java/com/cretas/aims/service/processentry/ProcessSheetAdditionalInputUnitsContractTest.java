package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessSheetAdditionalInputUnitsContractTest {
    @Test void upstreamKgAndPackagingCountsAreSeparateInDraftAndStructuredSubmission() {
        var service = mock(ProcessSheetServiceImpl.class, CALLS_REAL_METHODS);
        var descriptor = ProcessDescriptor.builder().processName("烘烤").inputs(List.of(
                port("bag", "RAW_MATERIAL", "个"), port("dough", "SEMI_FINISHED", "kg"))).build();
        var draft = new ProcessSheetRowRequest();
        assertThatCode(() -> apply(service, descriptor, draft)).doesNotThrowAnyException();
        assertThat(draft.getInputUnit()).isEqualTo("kg");
        var bag = new ProcessSheetRowRequest.MaterialInputTotal();
        bag.setWorkflowPortId("bag"); bag.setMaterialTypeId("bag"); bag.setUnit("个"); bag.setQuantity(BigDecimal.TEN);
        var dough = new ProcessSheetRowRequest.UpstreamRef();
        dough.setWorkflowPortId("dough"); dough.setSkuId("dough"); dough.setFeedQuantityKg(new BigDecimal("1.6"));
        var request = new ProcessSheetRowRequest();
        request.setMaterialInputTotals(List.of(bag)); request.setUpstreamSources(List.of(dough));
        assertThatCode(() -> apply(service, descriptor, request)).doesNotThrowAnyException();
        assertThat(request.getInputUnit()).isEqualTo("kg");
        assertThat(bag.getUnit()).isEqualTo("个");
        assertThat(bag.getQuantity()).isEqualByComparingTo("10");
        assertThat(dough.getFeedQuantityKg()).isEqualByComparingTo("1.6");
        bag.setUnit("kg");
        assertThatThrownBy(() -> apply(service, descriptor, request)).isInstanceOf(RuntimeException.class);
        bag.setUnit("个"); bag.setMaterialTypeId("unauthorized");
        assertThatThrownBy(() -> apply(service, descriptor, request)).isInstanceOf(RuntimeException.class);
    }

    @Test void heterogeneousUpstreamAndMissingRawUnitsStillFailClosed() {
        var service = mock(ProcessSheetServiceImpl.class, CALLS_REAL_METHODS);
        for (var inputs : List.of(
                List.of(port("a", "SEMI_FINISHED", "kg"), port("b", "SEMI_FINISHED", "个")),
                List.of(port("a", "RAW_MATERIAL", "kg"), port("b", "RAW_MATERIAL", "个")),
                List.of(port("a", "SEMI_FINISHED", "kg"), port("b", "RAW_MATERIAL", "")))) {
            var descriptor = ProcessDescriptor.builder().inputs(inputs).build();
            assertThatThrownBy(() -> apply(service, descriptor, new ProcessSheetRowRequest())).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void explicitRawInputsUseThePortUnitAndRejectClientOverrides() {
        var service = mock(ProcessSheetServiceImpl.class, CALLS_REAL_METHODS);
        var descriptor = ProcessDescriptor.builder().inputs(List.of(port("bag", "RAW_MATERIAL", "个"))).build();
        var raw = new ProcessSheetRowRequest.RawInput();
        raw.setWorkflowPortId("bag"); raw.setSkuId("bag"); raw.setQuantity(BigDecimal.TEN);
        var request = new ProcessSheetRowRequest(); request.setRawMaterialInputs(List.of(raw));
        assertThatCode(() -> apply(service, descriptor, request)).doesNotThrowAnyException();
        assertThat(raw.getUnit()).isEqualTo("个");
        raw.setUnit("kg");
        assertThatThrownBy(() -> apply(service, descriptor, request)).isInstanceOf(RuntimeException.class);
    }

    @Test void rawAllocationCannotReplaceOrRelabelTheUpstreamDenominator() throws Exception {
        var service = mock(ProcessSheetServiceImpl.class, CALLS_REAL_METHODS);
        var allocation = new ProductionStockAllocationService.PlannedAllocation(
                "bag", "batch", "batch", "warehouse", BigDecimal.TEN, "个", 1,
                "bag", "bag-node", "bag", "RAW_MATERIAL", BigDecimal.ONE, BigDecimal.TEN, false);
        var upstream = new ProcessSheetRowRequest.UpstreamRef(); upstream.setFeedQuantityKg(new BigDecimal("1.6"));
        var request = new ProcessSheetRowRequest(); request.setUpstreamSources(List.of(upstream));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,
                "applyAllocatedInputQuantity", request, List.of(allocation))).isInstanceOf(RuntimeException.class);
        request.setInputQuantity(new BigDecimal("1.6")); request.setInputUnit("kg");
        ReflectionTestUtils.invokeMethod(service, "applyAllocatedInputQuantity", request, List.of(allocation));
        assertThat(request.getInputQuantity()).isEqualByComparingTo("1.6");
        assertThat(request.getInputUnit()).isEqualTo("kg");
        request.setInputUnit("个");
        ReflectionTestUtils.invokeMethod(service, "applyAllocatedInputQuantity", request, List.of(allocation));
        assertThat(request.getInputUnit()).isEqualTo("个");
        var source = Files.readString(Path.of("src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java"));
        assertThat(source.split("applyAllocatedInputQuantity\\(req, allocations\\);", -1)).hasSize(3);
    }

    private void apply(ProcessSheetServiceImpl service, ProcessDescriptor descriptor, ProcessSheetRowRequest request) {
        ReflectionTestUtils.invokeMethod(service, "applyWorkflowInputPorts", "TEST", "bread", descriptor, request, false);
    }
    private PortDescriptor port(String id, String kind, String unit) {
        return PortDescriptor.builder().workflowPortId(id).skuId(id).materialKind(kind).unit(unit).build();
    }
}
