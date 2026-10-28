package com.cretas.aims.blueprint.interview;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.LineCapacityItem;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.ProductionLineItem;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.TypeItem;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.Source;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.scheduling.LineCapacityConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4 切片 2b: 对话建厂带上产线与每小时产能。
 * 校验在写入前判清 (单位 / 数值 / 产品 / 同名), 预览与落库回执用同一个键, 平台管理员也能落库,
 * 预览分组能被冻结的 v2 能力目录归类 (归不了类会挡模拟)。
 */
class BuildProductionLinesContractTest {

    private static final String F = "F-S4B";
    private static final ActorRef PLATFORM = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 3L);

    private final ProductTypeRepository products = mock(ProductTypeRepository.class);
    private final ProductionLineRepository lines = mock(ProductionLineRepository.class);
    private final ProductionLineCapacityConfigRepository capacity = mock(ProductionLineCapacityConfigRepository.class);
    private final LineCapacityConfigService writer = mock(LineCapacityConfigService.class);
    private final FactoryBuildApplyValidator validator = new FactoryBuildApplyValidator();
    private final FactoryBuildApplyService apply = new FactoryBuildApplyService();
    private final FactoryBuildPlanService planner = new FactoryBuildPlanService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(validator, "productTypeRepository", products);
        ReflectionTestUtils.setField(validator, "productionLineRepository", lines);
        ReflectionTestUtils.setField(apply, "productTypeRepository", products);
        ReflectionTestUtils.setField(apply, "productionLineRepository", lines);
        ReflectionTestUtils.setField(apply, "lineCapacityConfigService", writer);
        ReflectionTestUtils.setField(planner, "productTypeRepository", products);
        ReflectionTestUtils.setField(planner, "productionLineRepository", lines);
        ReflectionTestUtils.setField(planner, "lineCapacityConfigRepository", capacity);
        when(lines.findByFactoryIdAndNameAndDeletedAtIsNull(anyString(), anyString())).thenReturn(List.of());
        when(products.findByFactoryIdAndCode(anyString(), anyString())).thenReturn(Optional.empty());
        when(lines.save(any(ProductionLine.class))).thenAnswer(inv -> {
            ProductionLine l = inv.getArgument(0);
            l.setId("NEW-" + l.getName());
            return l;
        });
    }

    private static LineCapacityItem cap(String productCode, String rate, String unit) {
        LineCapacityItem c = new LineCapacityItem();
        c.setProductCode(productCode);
        c.setCapacityPerHour(rate == null ? null : new BigDecimal(rate));
        c.setCapacityUnit(unit);
        return c;
    }

    private static ProductionLineItem line(String name, LineCapacityItem... caps) {
        ProductionLineItem l = new ProductionLineItem();
        l.setName(name);
        l.setCapacities(List.of(caps));
        return l;
    }

    private static ApplyAIInitRequest request(ProductionLineItem... items) {
        ApplyAIInitRequest r = new ApplyAIInitRequest();
        ApplyAIInitRequest.SuggestedData sd = new ApplyAIInitRequest.SuggestedData();
        TypeItem beef = new TypeItem();
        beef.setCode("P-BEEF");
        beef.setName("卤牛肉");
        sd.setProductTypes(List.of(beef));
        sd.setProductionLines(List.of(items));
        r.setSuggestedData(sd);
        return r;
    }

    private List<String> problems(ApplyAIInitRequest r) {
        List<String> out = new ArrayList<>();
        validator.checkProductionLines(out, r.getSuggestedData(), F);
        return out;
    }

    // ---------------------------------------------------------------- validator

    @Test
    void aWellFormedAnswerPasses() {
        assertThat(problems(request(
                line("卤制线", cap(null, "200", "KG"), cap("P-BEEF", "50", "kg")),
                line("包装线", cap(null, "1500", "PIECE"))))).isEmpty();
    }

    @Test
    void everythingThatWouldFailMidWriteIsRejectedBeforeAnyWrite() {
        List<String> p = problems(request(
                line("烤线", cap(null, "3", "斤")),
                line("冷却线", cap(null, "0", "KG")),
                line("杀菌线", cap("P-NOPE", "2", "BATCH")),
                line("包装线", cap(null, "10", "PIECE"), cap(null, "20", "PIECE")),
                line("包装线", cap(null, "10", "PIECE")),
                line(" ")));
        System.out.println("BUILD_LINE_PROBLEMS " + p);
        assertThat(p).anyMatch(s -> s.contains("capacityUnit=斤"))
                .anyMatch(s -> s.contains("capacityPerHour 必须大于 0"))
                .anyMatch(s -> s.contains("productCode=P-NOPE"))
                .anyMatch(s -> s.contains("同一产品给了两次"))
                .anyMatch(s -> s.contains("「包装线」在本次请求里重复"))
                .anyMatch(s -> s.contains(".name 为空"));
    }

    @Test
    void twoExistingLinesWithTheSameNameAreRejectedInsteadOfGuessed() {
        when(lines.findByFactoryIdAndNameAndDeletedAtIsNull(F, "卤制线"))
                .thenReturn(List.of(new ProductionLine(), new ProductionLine()));
        assertThat(problems(request(line("卤制线", cap(null, "200", "KG")))))
                .singleElement().asString().contains("库里已有 2 条叫「卤制线」");
    }

    // ------------------------------------------------------- apply + preview parity

    @Test
    void applyCreatesNewLinesReusesExistingOnesAndWritesCapacityAsTheBuildActor() {
        ProductionLine existing = new ProductionLine();
        existing.setId("L-OLD");
        existing.setName("包装线");
        when(lines.findByFactoryIdAndNameAndDeletedAtIsNull(F, "包装线")).thenReturn(List.of(existing));
        ProductType beef = new ProductType();
        beef.setId("PT-BEEF-ID");
        when(products.findByFactoryIdAndCode(F, "P-BEEF")).thenReturn(Optional.of(beef));
        ApplyAIInitRequest r = request(
                line("卤制线", cap(null, "200", "KG"), cap("P-BEEF", "50", "KG")),
                line("包装线", cap(null, "1500", "PIECE")));

        List<PlannedChange> planned = planner.planChanges(F, r, false);
        ApplyAIInitResult result = new ApplyAIInitResult();
        ReflectionTestUtils.invokeMethod(apply, "applyProductionLines", F, r, PLATFORM, result);

        verify(lines).save(any(ProductionLine.class));   // 只新建卤制线, 包装线复用
        verify(writer).setCapacity(eq(F), eq("NEW-卤制线"), isNull(), eq(new BigDecimal("200")), eq("KG"),
                eq(Source.CONVERSATIONAL_BUILD), eq(PLATFORM));
        verify(writer).setCapacity(eq(F), eq("NEW-卤制线"), eq("PT-BEEF-ID"), eq(new BigDecimal("50")), eq("KG"),
                eq(Source.CONVERSATIONAL_BUILD), eq(PLATFORM));
        verify(writer).setCapacity(eq(F), eq("L-OLD"), isNull(), eq(new BigDecimal("1500")), eq("PIECE"),
                eq(Source.CONVERSATIONAL_BUILD), eq(PLATFORM));
        assertThat(result.getSkippedProductionLines()).containsExactly("产线 包装线");
        assertThat(result.getWrittenProductionLines())
                .containsExactly("产线 卤制线", "卤制线 · 所有产品", "卤制线 · P-BEEF", "包装线 · 所有产品");

        // 预览与回执逐键对得上: 预览里的每一项都出现在 written 或 skipped
        List<PlannedChange> group = planned.stream().filter(c -> "产线与设备产能".equals(c.getGroup())).toList();
        List<String> done = new ArrayList<>(result.getWrittenProductionLines());
        done.addAll(result.getSkippedProductionLines());
        assertThat(group).extracting(PlannedChange::getKey).containsExactlyInAnyOrderElementsOf(done);
        assertThat(group).filteredOn(c -> c.getKey().equals("产线 包装线")).singleElement()
                .extracting(PlannedChange::getAction).isEqualTo("跳过");
        assertThat(group).filteredOn(c -> c.getKey().equals("卤制线 · P-BEEF")).singleElement()
                .extracting(PlannedChange::getDetail).isEqualTo("卤牛肉 每小时 50 kg");
    }

    @Test
    void anExistingActiveCapacityIsPreviewedAsOverwrite() {
        ProductionLine existing = new ProductionLine();
        existing.setId("L-OLD");
        when(lines.findByFactoryIdAndNameAndDeletedAtIsNull(F, "包装线")).thenReturn(List.of(existing));
        when(capacity.findByFactoryIdAndProductionLineIdAndProductTypeId(F, "L-OLD", null))
                .thenReturn(Optional.of(new ProductionLineCapacityConfig()));

        List<PlannedChange> planned = planner.planChanges(F, request(line("包装线", cap(null, "1500", "PIECE"))), false);

        assertThat(planned).filteredOn(c -> c.getKey().equals("包装线 · 所有产品")).singleElement()
                .extracting(PlannedChange::getAction).isEqualTo("覆盖");
    }

    @Test
    void noLinesInTheBuildWritesNothing() {
        ApplyAIInitRequest r = request();
        r.getSuggestedData().setProductionLines(null);
        ApplyAIInitResult result = new ApplyAIInitResult();
        ReflectionTestUtils.invokeMethod(apply, "applyProductionLines", F, r, PLATFORM, result);
        verify(lines, never()).save(any());
        verify(writer, never()).setCapacity(any(), any(), any(), any(), any(), any(), any());
        assertThat(result.getWrittenProductionLines()).isEmpty();
    }

    // ------------------------------------------------------------- catalog

    /**
     * 冻结的 v2 能力目录 (V20261103_61) 按 display_order 取第一个关键词命中的能力;
     * 归不了类的预览分组会挡模拟 (UNCLASSIFIED_PLANNED_CHANGE)。这里照同一规则读迁移里的那几行。
     */
    @Test
    void thePreviewGroupIsClassifiedByTheFrozenV2Catalog() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261103_61__assembly_catalog_and_snapshot_dependencies.sql"),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\('factory-capability-catalog/v2', '([a-z-]+)', '[^']*', '[^']*', (\\d+),\\s*"
                + "'[A-Z_]+', '\\[[^\\]]*\\]', '\\[([^\\]]*)\\]'").matcher(sql);
        record Row(String id, int order, List<String> keywords) {}
        List<Row> rows = new ArrayList<>();
        while (m.find()) {
            List<String> kws = new ArrayList<>();
            Matcher k = Pattern.compile("\"([^\"]+)\"").matcher(m.group(3));
            while (k.find()) {
                kws.add(k.group(1));
            }
            rows.add(new Row(m.group(1), Integer.parseInt(m.group(2)), kws));
        }
        assertThat(rows).hasSizeGreaterThanOrEqualTo(26);   // 读到了整张目录, 不是恒绿
        String group = "产线与设备产能";
        String hit = rows.stream().sorted(Comparator.comparingInt(Row::order))
                .filter(r -> !("processes".equals(r.id()) && group.contains("工序链")))
                .filter(r -> r.keywords().stream().anyMatch(group::contains))
                .map(Row::id).findFirst().orElse("UNCLASSIFIED");
        assertThat(hit).isEqualTo("equipment-calibration");
    }
}
