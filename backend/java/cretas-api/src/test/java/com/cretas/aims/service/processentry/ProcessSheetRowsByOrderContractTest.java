package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowView;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逐道录入的行 / 库存按 processOrder 取, ⛔ 不再拿 processCode 一起过滤。
 *
 * <p>processCode 是前端按工序名 / 投入形态推出来的 archetype, 推导规则一变, 旧行就对不上。
 * 2026-10-25 F006 L4 实测: 分割已正式报了 4 行 (存的 processCode=shuzhi), 页面现在按 chaoshui 查 ⇒
 * 分割 tab 显示「待报工」, 吃分割产出的下游来源批次为空。processOrder 链内唯一, 已足够隔离各道。
 */
@DisplayName("逐道录入: 行按 processOrder 取, processCode 漂了也找得到")
class ProcessSheetRowsByOrderContractTest {

    @Test
    void rowsSavedUnderAnOlderProcessCodeAreStillReturned() {
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        ProcessSheetRow saved = new ProcessSheetRow();
        saved.setProcessCode("shuzhi");   // 保存时推出来的 code
        saved.setProcessOrder(2);
        saved.setClientRowId("r1");
        saved.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessOrder("F006", "P1", 2)).thenReturn(List.of(saved));

        ProcessSheetServiceImpl svc = construct(Map.of(ProcessSheetRowRepository.class, rowRepo));

        // 现在页面推出来的 code 是 chaoshui
        List<ProcessSheetRowView> rows = svc.getRows("F006", "P1", "chaoshui", 2);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getClientRowId()).isEqualTo("r1");
        verify(rowRepo, never()).findByFactoryIdAndPlanIdAndProcessCodeAndProcessOrder(
                anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void inventoryAlsoLooksUpByOrder() {
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessOrder(any(), any(), any())).thenReturn(List.of());
        ProcessSheetServiceImpl svc = construct(Map.of(ProcessSheetRowRepository.class, rowRepo));

        svc.getInventory("F006", "P1", "chaoshui", 2);

        verify(rowRepo).findByFactoryIdAndPlanIdAndProcessOrder("F006", "P1", 2);
        verify(rowRepo, never()).findByFactoryIdAndPlanIdAndProcessCodeAndProcessOrder(
                anyString(), anyString(), anyString(), anyInt());
    }

    private static ProcessSheetServiceImpl construct(Map<Class<?>, Object> provided) {
        try {
            Constructor<?> ctor = Arrays.stream(ProcessSheetServiceImpl.class.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();
            Class<?>[] params = ctor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = provided.containsKey(params[i]) ? provided.get(params[i]) : Mockito.mock(params[i]);
            }
            ctor.setAccessible(true);
            return (ProcessSheetServiceImpl) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
