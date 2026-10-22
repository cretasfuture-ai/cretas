package com.cretas.aims.contract;

import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.controller.BomController;
import com.cretas.aims.controller.BomRecipeController;
import com.cretas.aims.controller.BomWorkflowRevisionController;
import com.cretas.aims.controller.ProductProcessWorkflowController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工艺保密接口台账 (方案 S0): BOM / 工艺控制器的<b>每一个</b>接口必须落在三类之一 ——
 * <ol>
 *   <li>带 {@link ProcessSecretGuard} (进入 controller 前判定);</li>
 *   <li>在方法体里显式调用工艺保密判定 (产品来自请求体 / 多产品), 列在 {@link #CHECKED_IN_BODY};</li>
 *   <li>列在 {@link #LEDGER} 并写明理由: 靠响应裁剪覆盖、只有元信息、或「V1 未纳管」的已知缺口。</li>
 * </ol>
 * 新增接口不归类 → 红。这样「漏了一个出口」不会静默发生, 已知缺口也写在明处而不是藏在报告里。
 */
class ProcessSecretRouteLedgerContractTest {

    static final List<Class<?>> CONTROLLERS = List.of(BomRecipeController.class, BomController.class,
            ProductProcessWorkflowController.class, BomWorkflowRevisionController.class);

    static final Set<String> CHECKED_IN_BODY = Set.of(
            "BomRecipeController#createRecipe",
            "BomRecipeController#ensureDraft",
            "BomRecipeController#copyToDraft",
            "BomController#calculateCostsBatch",
            "BomController#exportWorkflowBom");

    static final Map<String, String> LEDGER = Map.ofEntries(
            Map.entry("BomRecipeController#listRecipes", "响应裁剪: 受保护产品的配方实体被换成摘要"),
            Map.entry("BomRecipeController#getRecipe", "响应裁剪: 详情返回摘要 + RESTRICTED (页面可显示抬头而非报错)"),
            Map.entry("BomRecipeController#getCurrentByProduct", "响应裁剪: 同上"),
            Map.entry("BomRecipeController#getVersionsByProduct", "响应裁剪: 版本列表每条换成摘要"),
            Map.entry("BomRecipeController#getCopyCandidates", "V1 未纳管: 候选 DTO 含其他产品的规则摘要, S1 补"),
            Map.entry("ProductProcessWorkflowController#getEditorDefinition", "响应裁剪: DTO 换成摘要, 节点不下发"),
            Map.entry("ProductProcessWorkflowController#listVersions", "只有版本号/状态/时间元信息"),
            Map.entry("ProductProcessWorkflowController#getActivation", "只有激活状态元信息"),
            Map.entry("ProductProcessWorkflowController#resolveByOutputs", "V1 未纳管: 按产出反查工艺, S1 补"),
            Map.entry("ProductProcessWorkflowController#findWorkflowsProducing", "V1 未纳管: 产出目录, S1 补"),
            Map.entry("BomController#getProductTypesWithBom", "只有产品 ID 列表"),
            Map.entry("BomController#recalculatePreview", "V1 未纳管: 出成率重算预览 (需 bom:yield_standard 权限), S1 补"),
            Map.entry("BomController#recalculateApply", "V1 未纳管: 出成率批量应用 (需 bom:yield_standard:write), S1 补"),
            Map.entry("BomController#listYieldSuggestions", "V1 未纳管: 全厂出成率建议, S1 补"));

    @Test
    void everyEndpointIsGuardedCheckedInBodyOrLedgered() {
        List<String> unclassified = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) continue;
                String key = controller.getSimpleName() + "#" + method.getName();
                seen.add(key);
                boolean guarded = method.isAnnotationPresent(ProcessSecretGuard.class);
                int classes = (guarded ? 1 : 0) + (CHECKED_IN_BODY.contains(key) ? 1 : 0)
                        + (LEDGER.containsKey(key) ? 1 : 0);
                if (classes != 1) unclassified.add(key + " (classified " + classes + " times)");
            }
        }
        assertThat(unclassified).as("每个接口恰好归入一类").isEmpty();
        // 台账里不能留已删除接口的条目 (否则台账会悄悄变成摆设)
        assertThat(seen).containsAll(LEDGER.keySet()).containsAll(CHECKED_IN_BODY);
    }

    @Test
    void guardNamesPointAtRealParametersOfTheRightKind() {
        List<String> broken = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                ProcessSecretGuard guard = method.getAnnotation(ProcessSecretGuard.class);
                if (guard == null) continue;
                boolean wantParam = guard.from() == ProcessSecretGuard.Source.PRODUCT_PARAM;
                boolean found = false;
                for (Parameter p : method.getParameters()) {
                    PathVariable pv = p.getAnnotation(PathVariable.class);
                    RequestParam rp = p.getAnnotation(RequestParam.class);
                    if (!wantParam && pv != null && guard.name().equals(boundName(pv.value(), pv.name(), p))) found = true;
                    if (wantParam && rp != null && guard.name().equals(boundName(rp.value(), rp.name(), p))) found = true;
                }
                if (!found) broken.add(controller.getSimpleName() + "#" + method.getName() + " -> " + guard.name());
            }
        }
        assertThat(broken).isEmpty();
    }

    @Test
    void bodyCheckedEndpointsReallyCallTheAccessService() throws IOException {
        for (String key : CHECKED_IN_BODY) {
            String[] parts = key.split("#");
            Path source = Path.of("src/main/java/com/cretas/aims/controller/" + parts[0] + ".java");
            String text = Files.readString(source, StandardCharsets.UTF_8);
            int start = text.indexOf(" " + parts[1] + "(");
            assertThat(start).as(key).isPositive();
            int end = text.indexOf("\n    }", start);
            assertThat(text.substring(start, end)).as(key + " 方法体").contains("processSecretAccessService.");
        }
    }

    private static String boundName(String value, String name, Parameter p) {
        if (!value.isEmpty()) return value;
        if (!name.isEmpty()) return name;
        return p.getName();
    }
}
