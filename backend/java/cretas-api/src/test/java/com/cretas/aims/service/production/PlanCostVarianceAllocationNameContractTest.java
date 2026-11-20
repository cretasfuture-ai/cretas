package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多产出分摊明细的产品名: 产出行的 productTypeId 可能是原料/流程占位 (RMT_…), 产品档案查不到时
 * 原先 productName=null, 前端把内部 id 当名字显示 (F006 四个计划, 2026-11-13 验收 09)。
 */
class PlanCostVarianceAllocationNameContractTest {

    private static final String PH = "RMT_74b0b790-c422-46df-804e-facf57deb8a1";

    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final ProductTypeRepository products = mock(ProductTypeRepository.class);
    private final ProcessSheetRowRepository rows = mock(ProcessSheetRowRepository.class);
    private final PlanCostVarianceService service = new PlanCostVarianceService(
            mock(PlanStandardCostLineRepository.class), mock(ProductionPlanRepository.class),
            mock(ProductionBatchRepository.class), mock(MaterialConsumptionRepository.class),
            mock(MaterialBatchRepository.class), materials, mock(OrderCostBreakdownService.class),
            mock(UnitConversionService.class));

    PlanCostVarianceAllocationNameContractTest() {
        ReflectionTestUtils.setField(service, "processSheetRowRepository", rows);
        ReflectionTestUtils.setField(service, "productTypeRepository", products);
        when(products.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(materials.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private static ProcessSheetRow row(String clientRowId, String productTypeId) {
        ProcessSheetRow r = new ProcessSheetRow();
        r.setFactoryId("F006");
        r.setPlanId("P1");
        r.setProcessCode("CUT");
        r.setProcessOrder(1);
        r.setClientRowId(clientRowId);
        r.setBatchNumber("B-" + clientRowId);
        r.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        r.setRowPayload("{\"multiOutputMember\":true,\"multiOutputBaseRowId\":\"base\",\"costAllocationBasis\":\"MASS\","
                + "\"productTypeId\":\"" + productTypeId + "\",\"costAllocationRatio\":\"50\"}");
        return r;
    }

    private List<String> names(String... productTypeIds) {
        List<ProcessSheetRow> list = new java.util.ArrayList<>();
        for (int i = 0; i < productTypeIds.length; i++) {
            list.add(row("r" + i, productTypeIds[i]));
        }
        when(rows.findByFactoryIdAndPlanId("F006", "P1")).thenReturn(list);
        return service.allocationMethods("F006", "P1").stream()
                .flatMap(g -> g.getOutputs().stream()).map(PlanCostVarianceDTO.AllocationOutput::getProductName).toList();
    }

    @Test
    void placeholderIdThatResolvesNowhereIsShownAsUnknownMaterialNotTheInternalId() {
        // 阳性样本: 源数据确是 RMT_ 占位, 产品档案和原料档案都查不到
        assertThat(PH).startsWith("RMT_");
        List<String> n = names(PH);
        assertThat(n).containsExactly(PlanCostVarianceService.UNKNOWN_MATERIAL_NAME);
        assertThat(n).noneMatch(s -> s == null || s.contains("RMT_") || s.contains("__RAW_"));
    }

    @Test
    void placeholderIdResolvesToTheRawMaterialNameWhenArchiveHasIt() {
        RawMaterialType rm = new RawMaterialType();
        rm.setName("安格斯碎肉");
        when(materials.findByIdAndFactoryId(PH, "F006")).thenReturn(Optional.of(rm));
        when(products.findByIdAndFactoryId(PH, "F006")).thenReturn(Optional.empty());
        assertThat(names(PH)).containsExactly("安格斯碎肉");
    }

    @Test
    void ordinaryProductKeepsItsName() {
        ProductType pt = new ProductType();
        pt.setId("SKU-1");
        pt.setName("卤牛肉");
        when(products.findByIdAndFactoryId("SKU-1", "F006")).thenReturn(Optional.of(pt));
        assertThat(names("SKU-1")).containsExactly("卤牛肉");
    }
}
