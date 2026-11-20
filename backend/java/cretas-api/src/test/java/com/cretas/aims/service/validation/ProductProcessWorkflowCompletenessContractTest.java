package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowDTO.Edge;
import com.cretas.aims.dto.ProductProcessWorkflowDTO.Node;
import com.cretas.aims.dto.ProductProcessWorkflowDTO.Position;
import com.cretas.aims.dto.workflow.WorkflowCompletenessIssue;
import com.cretas.aims.dto.workflow.WorkflowUnitIssueDTO;
import com.cretas.aims.dto.workflow.WorkflowUnitValidationResult;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductProcessWorkflowCompletenessContractTest {
    private static final String FACTORY = "F006";
    private static final String PRODUCT = "mock-400g";
    private final ProductProcessWorkflowCatalogValidator catalog = mock(ProductProcessWorkflowCatalogValidator.class);
    private final ProductProcessWorkflowUnitValidator units = mock(ProductProcessWorkflowUnitValidator.class);
    private final ProductProcessWorkflowRuntimeCompiler compiler = mock(ProductProcessWorkflowRuntimeCompiler.class);
    private final UnitContractService unitContracts = mock(UnitContractService.class);
    private final ProductTypeRepository products = mock(ProductTypeRepository.class);
    private final ProductWorkProcessRepository productProcesses = mock(ProductWorkProcessRepository.class);
    private final ProductProcessWorkflowValidator structure = new ProductProcessWorkflowValidator();
    private final ProductProcessWorkflowCompletenessChecker checker = new ProductProcessWorkflowCompletenessChecker(
            structure, catalog, units, compiler, unitContracts, products, productProcesses);

    @BeforeEach
    void setUp() {
        when(units.validate(eq(FACTORY), any())).thenReturn(new WorkflowUnitValidationResult(List.of(), List.of()));
        unit("g", UnitDimension.MASS);
        unit("kg", UnitDimension.MASS);
        unit("盒", UnitDimension.PACKAGE);
        unit("袋", UnitDimension.PACKAGE);
        unit("个", UnitDimension.COUNT);
        unit("box", UnitDimension.PACKAGE);
        unit("case", UnitDimension.PACKAGE);
        when(unitContracts.convert(any(UnitConversionContext.class))).thenAnswer(call -> {
            UnitConversionContext context = call.getArgument(0);
            boolean mass = List.of("g", "kg").contains(context.fromUnit())
                    && List.of("g", "kg").contains(context.toUnit());
            return new UnitConversionResult(mass ? UnitConversionStatus.CONVERTED : UnitConversionStatus.UNKNOWN_UNIT,
                    null, context.fromUnit(), context.toUnit(), List.of(), null, null, null);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESS_CATEGORY_REQUIRED", "INPUT_REQUIRED", "OUTPUT_REQUIRED",
            "OUTPUT_UNCONNECTED", "FINISHED_OUTPUT_REQUIRED", "RAW_INPUT_REQUIRED",
            "INPUT_UNIT_REQUIRED", "OUTPUT_UNIT_REQUIRED", "COUNT_WEIGHT_REQUIRED", "CONVERSION_REQUIRED",
            "AUX_USAGE_REQUIRED", "PACKAGING_USAGE_REQUIRED", "STANDARD_YIELD_INVALID",
            "FIXED_RATIO_REQUIRED", "INPUT_RATIO_INVALID", "MATERIAL_UNCONNECTED", "MATERIAL_BINDING_REQUIRED",
            "PENDING_CONFIRMATION", "DRAFT_GRAPH_INVALID"})
    void reportsEachOmissionAndAcceptsCompleteGraph(String rule) {
        ProductProcessWorkflowDTO graph = completeGraph();
        assertThat(check(graph)).isEmpty();
        Node process = graph.getNodes().get(1);
        Node finished = graph.getNodes().get(2);
        switch (rule) {
            case "PROCESS_CATEGORY_REQUIRED" -> process.getData().remove("processCategory");
            case "INPUT_REQUIRED" -> graph.getEdges().removeFirst();
            case "OUTPUT_REQUIRED" -> process.getData().put("ports", List.of(input()));
            case "OUTPUT_UNCONNECTED" -> {
                graph.getNodes().set(2, new Node(finished.getId(), "SEMI_FINISHED", finished.getPosition(), finished.getData()));
                process.getData().put("ports", List.of(input(), port("out", "OUTPUT", "finished", "SEMI_FINISHED", "kg")));
            }
            case "FINISHED_OUTPUT_REQUIRED" -> graph.getEdges().removeLast();
            case "RAW_INPUT_REQUIRED" -> graph.getNodes().removeFirst();
            case "INPUT_UNIT_REQUIRED" -> process.getData().put("ports", List.of(port("in", "INPUT", "raw", "RAW_MATERIAL", ""), output("kg")));
            case "OUTPUT_UNIT_REQUIRED" -> process.getData().put("ports", List.of(input(), output("")));
            case "COUNT_WEIGHT_REQUIRED", "CONVERSION_REQUIRED" -> setOutputUnit(graph, "盒");
            case "AUX_USAGE_REQUIRED" -> process.getData().put("materialBindings",
                    List.of(Map.of("materialTypeId", "aux", "materialName", "八角")));
            case "PACKAGING_USAGE_REQUIRED" -> finished.getData().put("packagingBindings",
                    List.of(Map.of("materialTypeId", "label", "materialName", "标签", "standardQuantity", 0)));
            case "STANDARD_YIELD_INVALID" -> process.getData().remove("standardYieldRate");
            case "FIXED_RATIO_REQUIRED" -> process.getData().put("conversionRule", Map.of("mode", "FIXED_RATIO"));
            case "INPUT_RATIO_INVALID" -> {
                Map<String, Object> input = input();
                input.put("inputShareRatio", 101);
                process.getData().put("ports", List.of(input, output("kg")));
            }
            case "MATERIAL_UNCONNECTED" -> graph.getEdges().removeFirst();
            case "MATERIAL_BINDING_REQUIRED" -> finished.getData().remove("skuId");
            case "PENDING_CONFIRMATION" -> process.getData().put("pendingConfirmations",
                    List.of(Map.of("label", "主料配比待确认")));
            case "DRAFT_GRAPH_INVALID" -> graph.getEdges().add(graph.getEdges().getFirst());
            default -> throw new AssertionError(rule);
        }
        assertThat(check(graph)).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(rule);
            assertThat(issue.message()).isNotBlank();
            assertThat(issue.action()).isNotBlank();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.1", "1.001"})
    void checksYieldAsAFractionAndUsesExistingProductProcessAssignment(String bad) {
        var graph = completeGraph();
        graph.getNodes().get(1).getData().put("standardYieldRate", bad);
        assertThat(codes(graph)).contains("STANDARD_YIELD_INVALID");
        graph.getNodes().get(1).getData().remove("standardYieldRate");
        var assignment = new ProductWorkProcess();
        assignment.setWorkProcessId("pack");
        assignment.setStandardYieldRate(new BigDecimal("0.85"));
        when(productProcesses.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY, PRODUCT))
                .thenReturn(List.of(assignment));
        assertThat(codes(graph)).doesNotContain("STANDARD_YIELD_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"盒", "袋", "个", "box", "case"})
    void testsCountOrPackageOutputWithAndWithoutSameUnitNetWeight(String outputUnit) {
        var graph = completeGraph();
        setOutputUnit(graph, outputUnit);
        assertThat(codes(graph)).contains("COUNT_WEIGHT_REQUIRED", "CONVERSION_REQUIRED");
        var product = new ProductType();
        product.setId(PRODUCT);
        product.setFactoryId(FACTORY);
        product.setUnit(outputUnit);
        product.setNetContentQuantity(new BigDecimal("400"));
        product.setNetContentUnit("g");
        when(products.findByIdIn(anyList())).thenReturn(List.of(product));
        assertThat(codes(graph)).doesNotContain("COUNT_WEIGHT_REQUIRED", "CONVERSION_REQUIRED");
        product.setFactoryId("F007");
        assertThat(codes(graph)).contains("COUNT_WEIGHT_REQUIRED", "CONVERSION_REQUIRED");
    }

    @Test
    void recognisesIntrinsicMassConversionAndPositiveFixedRatioAndPackaging() {
        var graph = completeGraph();
        setOutputUnit(graph, "g");
        assertThat(check(graph)).isEmpty();
        setOutputUnit(graph, "盒");
        graph.getNodes().get(1).getData().put("conversionRule",
                Map.of("mode", "FIXED_RATIO", "expression", "0.4 kg = 1 盒"));
        graph.getNodes().get(2).getData().put("packagingBindings",
                List.of(Map.of("materialTypeId", "label", "materialName", "标签", "standardQuantity", 1)));
        assertThat(check(graph)).isEmpty();
        graph.getNodes().get(1).getData().put("conversionRule",
                Map.of("mode", "FIXED_RATIO", "expression", "0 kg = 1 盒"));
        assertThat(codes(graph)).contains("FIXED_RATIO_REQUIRED", "CONVERSION_REQUIRED");
    }

    @Test
    void reusesExistingUnitCatalogAndRuntimeChecksWithReadableFeedback() {
        var graph = completeGraph();
        when(units.validate(eq(FACTORY), any())).thenReturn(new WorkflowUnitValidationResult(List.of(
                new WorkflowUnitIssueDTO("WORKFLOW_CONVERSION_STALE", "internal data.ports[0]", "process", "out",
                        "kg", "g")), List.of()));
        doThrow(new BusinessException("档案不匹配: finished")).when(catalog)
                .validateForBomConfiguration(eq(FACTORY), eq(PRODUCT), any());
        doThrow(new BusinessException("internal runtime error")).when(compiler).compile(any());
        assertThat(codes(graph)).contains("WORKFLOW_CONVERSION_STALE", "PUBLISH_CATALOG_INVALID", "PUBLISH_RUNTIME_INVALID");
        assertThat(check(graph)).noneMatch(issue -> issue.message().contains("internal"));
        assertThat(check(graph)).anyMatch(issue -> "process".equals(issue.nodeId()) && "out".equals(issue.portId()));
    }

    private void unit(String value, UnitDimension dimension) {
        when(unitContracts.describe(FACTORY, value)).thenReturn(Optional.of(
                new CanonicalUnit(value, dimension, value, BigDecimal.ONE, value, 2)));
    }

    private List<WorkflowCompletenessIssue> check(ProductProcessWorkflowDTO graph) {
        return checker.check(FACTORY, PRODUCT, graph);
    }

    private List<String> codes(ProductProcessWorkflowDTO graph) {
        return check(graph).stream().map(WorkflowCompletenessIssue::code).toList();
    }

    private ProductProcessWorkflowDTO completeGraph() {
        var graph = new ProductProcessWorkflowDTO();
        graph.setFactoryId(FACTORY);
        graph.setProductTypeId(PRODUCT);
        graph.setNodes(new ArrayList<>(List.of(
                new Node("raw", "RAW_MATERIAL", new Position(0D, 0D),
                        new LinkedHashMap<>(Map.of("name", "原料", "skuId", "raw-id", "baseUnit", "kg"))),
                new Node("process", "PROCESS", new Position(300D, 0D),
                        new LinkedHashMap<>(Map.of("processName", "拼装分装", "processCategory", "包装",
                                "workProcessId", "pack", "inputUnit", "kg", "outputUnit", "kg",
                                "ports", List.of(input(), output("kg")), "standardYieldRate", 1,
                                "conversionRule", Map.of("mode", "ACTUAL_WEIGHT")))),
                new Node("finished", "FINISHED_GOOD", new Position(600D, 0D),
                        new LinkedHashMap<>(Map.of("name", "卤味三拼", "skuId", PRODUCT, "baseUnit", "kg"))))));
        graph.setEdges(new ArrayList<>(List.of(
                new Edge("input", "raw", "output", "process", "in"),
                new Edge("output", "process", "out", "finished", "input"))));
        return graph;
    }

    private void setOutputUnit(ProductProcessWorkflowDTO graph, String unit) {
        graph.getNodes().get(1).getData().put("outputUnit", unit);
        graph.getNodes().get(1).getData().put("ports", List.of(input(), output(unit)));
        graph.getNodes().get(2).getData().put("baseUnit", unit);
    }

    private static Map<String, Object> input() {
        return port("in", "INPUT", "raw", "RAW_MATERIAL", "kg");
    }

    private static Map<String, Object> output(String unit) {
        return port("out", "OUTPUT", "finished", "FINISHED_GOOD", unit);
    }

    private static Map<String, Object> port(String id, String direction, String materialId, String kind, String unit) {
        return new LinkedHashMap<>(Map.of("id", id, "direction", direction, "ordinal", 0,
                "materialNodeId", materialId, "materialKind", kind, "unit", unit));
    }
}
