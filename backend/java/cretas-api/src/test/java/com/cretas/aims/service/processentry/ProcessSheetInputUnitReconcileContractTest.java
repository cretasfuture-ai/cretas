package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.unit.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺口21 同类: 工艺图投入端口单位是权威值, 服务端会把行级 inputUnit 改写成端口单位 ——
 * 只改标签不改数量, 4 只就成了 4 kg (F006 2026-11-03 实测, 调料按 4 kg 算)。
 * 现在改写前必须先换算, 换不了就 409。
 */
class ProcessSheetInputUnitReconcileContractTest {

    private final UnitContractService units = mock(UnitContractService.class);
    private final ProcessSheetServiceImpl service = service();

    private ProcessSheetServiceImpl service() {
        ProcessSheetServiceImpl s = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(s, "unitContractService", units);
        return s;
    }

    private ProcessSheetRowRequest row(String unit, String qty) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setProductTypeId("PT1");
        r.setInputQuantity(new BigDecimal(qty));
        r.setInputUnit(unit);
        return r;
    }

    /** 走真实覆盖点: 调用点先 reconcileInputUnitBeforeAllocation, 再 applyAllocatedInputQuantity (无上游)。 */
    private void apply(ProcessSheetRowRequest req) throws Throwable {
        try {
            Method pre = ProcessSheetServiceImpl.class.getDeclaredMethod(
                    "reconcileInputUnitBeforeAllocation", String.class, ProcessSheetRowRequest.class);
            pre.setAccessible(true);
            pre.invoke(service, "F006", req);
            Method m = ProcessSheetServiceImpl.class.getDeclaredMethod(
                    "applyAllocatedInputQuantity", ProcessSheetRowRequest.class, List.class);
            m.setAccessible(true);
            m.invoke(null, req, List.of());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void piecesAreConvertedNotRelabelledAsKg() throws Throwable {
        when(units.areEquivalent(any(), any(), any())).thenReturn(false);
        when(units.convert(any(BigDecimal.class), any(UnitConversionContext.class))).thenReturn(
                new UnitConversionResult(UnitConversionStatus.CONVERTED, new BigDecimal("6"), "只", "kg", List.of(), null, null, null));
        ProcessSheetRowRequest req = row("只", "4");
        apply(req);
        assertThat(req.getInputUnit()).isEqualTo("kg");
        assertThat(req.getInputQuantity()).isEqualByComparingTo("6");
    }

    @Test
    void unconvertibleUnitIsRejectedNotRelabelled() {
        when(units.areEquivalent(any(), any(), any())).thenReturn(false);
        when(units.convert(any(BigDecimal.class), any(UnitConversionContext.class))).thenReturn(
                new UnitConversionResult(UnitConversionStatus.PRODUCT_CONVERSION_MISSING, null, "只", "kg", List.of(), null, null, "缺单重"));
        ProcessSheetRowRequest req = row("只", "4");
        Throwable e = assertThrows(Throwable.class, () -> apply(req));
        assertThat(e).isInstanceOf(BusinessException.class);
        assertThat(req.getInputQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void sameUnitAndUndeclaredUnitAreUntouched() throws Throwable {
        ProcessSheetRowRequest a = row("kg", "4");
        apply(a);
        assertThat(a.getInputQuantity()).isEqualByComparingTo("4");
        ProcessSheetRowRequest b = row(null, "4");
        apply(b);
        assertThat(b.getInputQuantity()).isEqualByComparingTo("4");
        assertThat(b.getInputUnit()).isEqualTo("kg");
    }

    /** 数覆盖点: 每个 req.setInputUnit( 要么前面有 reconcileClientInputUnit, 要么显式登记 guarded。 */
    @Test
    void everyInputUnitOverrideIsReconciledOrExplicitlyGuarded() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java"));
        String[] lines = src.split("\\R");
        int overrides = 0, reconciled = 0, guarded = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("//") || t.startsWith("*") || !t.contains("req.setInputUnit(")) {
                continue;
            }
            overrides++;
            String prev = (i > 1 ? lines[i - 2] : "") + " " + (i > 0 ? lines[i - 1] : "");
            if (prev.contains("reconcileClientInputUnit(")) {
                reconciled++;
            } else if (prev.contains("input-unit-override: guarded")) {
                guarded++;
            }
        }
        assertThat(overrides).as("覆盖点总数 (阳性对照: 扫得到)").isGreaterThanOrEqualTo(6);
        assertThat(reconciled + guarded).as("未换算也未登记 guarded 的覆盖点").isEqualTo(overrides);
        assertThat(reconciled).isGreaterThanOrEqualTo(3);
        assertThat(src.split("reconcileInputUnitBeforeAllocation\\(factoryId, req\\);", -1))
                .as("applyAllocatedInputQuantity 的两个调用点都要先换算").hasSize(3);
    }
}
