package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 对话建厂第二批厂规开关 (2026-11-13): 袋/盒产能核对、缺料自动关闭、质检次要项、销售批准后取消、超收(模式+比例)、
 * 插单一律审批、客供料多收。落点是工厂阈值, 和业务阈值页同一份数据; 默认值不变 (选「按系统默认」= 不写)。
 */
class BuildSwitchThresholdsContractTest {

    private static final String F = "F-SWITCH";
    /** 访谈里新增的题 -> 对应的阈值 key。 */
    private static final Map<String, String> CHOICE_QUESTIONS = Map.of(
            "packageUnitCheck", "production.capacity.package_unit_mode",
            "shortageClose", "shortage.case.close_mode",
            "inspectionFailScope", "quality.inspection.fail_scope",
            "salesCancelAfterApproval", "sales.order.cancel_after_approval",
            "overReceive", "production.receipt.over_receive_mode",
            "urgentInsertApproval", "production.urgent_insert.always_approval",
            "customerSuppliedOverReceive", "inventory.customer_supplied.allow_over_receive");

    private final FactoryThresholdRepository rows = mock(FactoryThresholdRepository.class);
    private final ThresholdResolverService resolver = mock(ThresholdResolverService.class);
    private final AssemblyThresholdWriter writer = new AssemblyThresholdWriter(rows, resolver);

    @SuppressWarnings("unchecked")
    private static List<com.cretas.aims.service.FactoryAssemblyConversationService.RequiredParameter> params() {
        return (List<com.cretas.aims.service.FactoryAssemblyConversationService.RequiredParameter>)
                ReflectionTestUtils.getField(FactoryBuildInterviewService.class, "FACTORY_BUILD_PARAMS");
    }

    private void registerType(String key, ThresholdValueType type) {
        when(rows.findByFactoryIdAndThresholdKey("*", key)).thenReturn(Optional.of(FactoryThreshold.builder()
                .factoryId("*").thresholdKey(key).valueType(type).build()));
    }

    // ------------------------------------------------------------------ 接上了没有 (访谈真的会问)

    @Test
    void everySwitchIsAskedAsAChoiceWithASystemDefaultOptionAndHasGuidance() {
        @SuppressWarnings("unchecked")
        Map<String, Object> guidance = (Map<String, Object>) ReflectionTestUtils.getField(FactoryDecisionGuidanceCatalog.class, "ROOT");
        for (String name : CHOICE_QUESTIONS.keySet()) {
            var p = params().stream().filter(x -> x.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("访谈里没有这一题: " + name));
            assertEquals("CHOICE", p.getInputType(), name);
            assertEquals("按系统默认", p.getOptions().get(p.getOptions().size() - 1), name + " 必须留「按系统默认」");
            assertTrue(guidance.containsKey(name), name + " 缺决策说明");
        }
        var rate = params().stream().filter(x -> x.getName().equals("overReceiveRate")).findFirst().orElseThrow();
        assertTrue(rate.isAllowNone());
        assertTrue(guidance.containsKey("overReceiveRate"));
    }

    // ------------------------------------------------------------------ 写进去的值读得出来 / 打错字写不进去

    @Test
    void theValuesTheInterviewProducesPassTheAssemblyThresholdValidation() {
        Map<String, String[]> good = Map.of(
                "production.capacity.package_unit_mode", new String[]{"NET_CONTENT", "FACTORY_UNIT"},
                "shortage.case.close_mode", new String[]{"AUTO", "MANUAL"},
                "quality.inspection.fail_scope", new String[]{"ANY_ITEM", "MINOR_FOR_REVIEW", "CRITICAL_ONLY"},
                "production.receipt.over_receive_mode", new String[]{"BLOCK", "WITHIN_RATE", "ALWAYS_WITH_REASON"});
        good.forEach((key, values) -> {
            registerType(key, ThresholdValueType.STRING);
            for (String v : values) {
                assertDoesNotThrow(() -> writer.validate(F, key, v), key + "=" + v);
            }
        });
        for (String key : List.of("sales.order.cancel_after_approval", "production.urgent_insert.always_approval",
                "inventory.customer_supplied.allow_over_receive")) {
            registerType(key, ThresholdValueType.INTEGER);
            assertDoesNotThrow(() -> writer.validate(F, key, "1"));
            assertDoesNotThrow(() -> writer.validate(F, key, "0"));
        }
        registerType("production.receipt.over_receive_rate", ThresholdValueType.DECIMAL);
        assertDoesNotThrow(() -> writer.validate(F, "production.receipt.over_receive_rate", "0.05"));
    }

    @Test
    void aMistypedSwitchCodeIsRefusedInsteadOfSilentlyFallingBackToTheDefault() {
        // 阴性: 之前建厂这条路只校验数字类型, 字符串开关写错字读取方会静默按默认/最严
        for (var bad : Map.of(
                "quality.inspection.fail_scope", "MINOR",
                "production.receipt.over_receive_mode", "ALLOW",
                "shortage.case.close_mode", "AUTOMATIC",
                "production.capacity.package_unit_mode", "KG").entrySet()) {
            registerType(bad.getKey(), ThresholdValueType.STRING);
            BusinessException e = assertThrows(BusinessException.class,
                    () -> writer.validate(F, bad.getKey(), bad.getValue()), bad.getKey());
            assertEquals(400, e.getCode());
        }
        verify(rows, never()).saveAndFlush(any());
    }

    @Test
    void aSwitchWrittenForTheFactoryNeverTouchesTheGlobalDefaultRow() {
        String key = "quality.inspection.fail_scope";
        FactoryThreshold global = FactoryThreshold.builder().factoryId("*").thresholdKey(key)
                .valueType(ThresholdValueType.STRING).thresholdValue("MINOR_FOR_REVIEW").defaultValue("MINOR_FOR_REVIEW").build();
        when(rows.findByFactoryIdAndThresholdKey("*", key)).thenReturn(Optional.of(global));
        writer.upsert(F, key, "ANY_ITEM");
        assertEquals("MINOR_FOR_REVIEW", global.getThresholdValue());
        verify(resolver).invalidate(F, key);
        assertThrows(BusinessException.class, () -> writer.upsert("*", key, "ANY_ITEM"));
    }
}
