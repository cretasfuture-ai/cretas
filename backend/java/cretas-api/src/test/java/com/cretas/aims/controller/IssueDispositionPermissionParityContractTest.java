package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.issue.IssueDisposition.SourceType;
import com.cretas.aims.service.issue.IssueDispositionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「发现的问题」处置的权限 = 来源页的权限, 两边一致 (注解里只能写字面量, 所以用反射直接读来源页的 @RequirePermission)。
 *
 * <p>写: 来源页「处理」动作 (可多个) 注解的并集; 读: 来源列表接口的注解。来源页改了权限而这里没跟 ⇒ 红。
 */
class IssueDispositionPermissionParityContractTest {

    private record Ref(Class<?> controller, List<String> methods) {
    }

    private static final Map<SourceType, Ref> WRITE_SOURCES = Map.of(
            SourceType.SHORTAGE_CASE, new Ref(ShortageCaseActionController.class, List.of("createDraft", "recheck", "decide")),
            SourceType.DELIVERY_RISK, new Ref(DeliveryRiskController.class, List.of("finish")),
            SourceType.PROCESS_PARAM_DEVIATION, new Ref(ProcessParamDeviationCaseController.class, List.of("resolve")),
            SourceType.COST_DEVIATION, new Ref(CostDeviationCaseController.class, List.of("resolve")),
            SourceType.COLD_CHAIN_ALERT, new Ref(ColdChainExposureController.class, List.of("hold")),
            SourceType.DAILY_RECONCILE_RUN, new Ref(CostDeviationCaseController.class, List.of("scan")));

    private static final Map<SourceType, Ref> READ_SOURCES = Map.of(
            SourceType.SHORTAGE_CASE, new Ref(ShortageCaseController.class, List.of("list")),
            SourceType.DELIVERY_RISK, new Ref(DeliveryRiskController.class, List.of("list")),
            SourceType.PROCESS_PARAM_DEVIATION, new Ref(ProcessParamDeviationCaseController.class, List.of("list")),
            SourceType.COST_DEVIATION, new Ref(CostDeviationCaseController.class, List.of("list")),
            SourceType.COLD_CHAIN_ALERT, new Ref(ColdChainExposureController.class, List.of("listAlerts")),
            SourceType.DAILY_RECONCILE_RUN, new Ref(CostDeviationCaseController.class, List.of("dailyRuns")));

    private static Set<String> annotated(Ref ref) {
        Set<String> out = new TreeSet<>();
        for (String name : ref.methods()) {
            List<Method> hits = Arrays.stream(ref.controller().getDeclaredMethods())
                    .filter(m -> m.getName().equals(name) && m.isAnnotationPresent(RequirePermission.class)).toList();
            assertThat(hits).as(ref.controller().getSimpleName() + "#" + name + " 应恰好一个带 @RequirePermission").hasSize(1);
            out.addAll(Arrays.asList(hits.get(0).getAnnotation(RequirePermission.class).value()));
        }
        assertThat(out).as("读到了注解 (不是空集上的恒真)").isNotEmpty();
        return out;
    }

    @Test
    void everySourceTypeIsCovered() {
        assertThat(WRITE_SOURCES.keySet()).isEqualTo(EnumSet.allOf(SourceType.class));
        assertThat(READ_SOURCES.keySet()).isEqualTo(EnumSet.allOf(SourceType.class));
        assertThat(IssueDispositionService.WRITE_PERMISSIONS.keySet()).isEqualTo(EnumSet.allOf(SourceType.class));
        assertThat(IssueDispositionService.READ_PERMISSIONS.keySet()).isEqualTo(EnumSet.allOf(SourceType.class));
    }

    @Test
    void handlingPermissionsEqualTheSourcePagesHandlingActions() {
        for (SourceType t : SourceType.values()) {
            assertThat(new TreeSet<>(IssueDispositionService.WRITE_PERMISSIONS.get(t)))
                    .as("写权限 " + t).isEqualTo(annotated(WRITE_SOURCES.get(t)));
        }
        // 评审点名的一条: 缺料是 采购 / 生产 / 调度, 没有仓库、质检
        assertThat(IssueDispositionService.WRITE_PERMISSIONS.get(SourceType.SHORTAGE_CASE))
                .containsExactlyInAnyOrder("procurement:read_write", "production:read_write", "scheduling:read_write");
    }

    @Test
    void readPermissionsEqualTheSourcePagesListEndpoints() {
        for (SourceType t : SourceType.values()) {
            assertThat(new TreeSet<>(IssueDispositionService.READ_PERMISSIONS.get(t)))
                    .as("读权限 " + t).isEqualTo(annotated(READ_SOURCES.get(t)));
        }
    }
}
