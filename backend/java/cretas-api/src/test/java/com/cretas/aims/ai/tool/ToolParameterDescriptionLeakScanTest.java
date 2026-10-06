package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.objenesis.ObjenesisStd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具参数描述不许漏类型名 / UUID / 驼峰字段名 (硬闸 + 棘轮)。
 *
 * <p>槽位填充 ({@code SlotFillingServiceImpl#inferSlotsFromTool}) 把<b>必填</b>参数的
 * {@code properties.<参数>.description} 原样当「缺参追问」发给用户, 所以
 * 「告警事件 ID (UUID)」「工序任务ID (Long)」「customerId 未提供时…」会原样出现在一线的手机上。
 * 2026-10-06 prod 实测 + Line B 复核: 至少 9 个工具这样漏。
 *
 * <p>扫描方式不是文本 grep 源码: 扫 classpath 上全部 {@link AbstractBusinessTool} 具体子类,
 * 用 Objenesis 绕过构造器造实例 (schema 方法不依赖注入的协作者), 调真实的
 * {@code getParametersSchema()}, 递归读每个属性的 {@code description} ——
 * 与槽位填充读到的是同一份数据。
 *
 * <p>三层:
 * <ol>
 *   <li>硬闸: 任何参数描述都不许出现 UUID 或类型标注 「(Long)」等 (工序任务工具已随同批修复, 无豁免);</li>
 *   <li>硬闸: <b>必填</b>参数描述不许出现驼峰字段名 —— 例外只有 {@code canvas_*} 画布设计工具
 *       (设计者要填的就是英文驼峰字段代码, 例子必须是驼峰);</li>
 *   <li>棘轮: <b>可选</b>参数描述里的驼峰字段名 (LLM 要照着产出 JSON 键, 如 {additiveCode, usageAmount})
 *       只许减不许增, 上限 {@link #OPTIONAL_CAMEL_CEILING}。</li>
 * </ol>
 */
@DisplayName("工具参数描述不漏类型名 / UUID / 驼峰字段名")
class ToolParameterDescriptionLeakScanTest {

    private static final Pattern TYPE_ANNOTATION = Pattern.compile(
            "\\((?:Long|Integer|String|Boolean|BigDecimal|Double|Float|Short|int|long|boolean|double)\\)");
    private static final Pattern UUID_WORD = Pattern.compile("\\bUUID\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMEL_CASE = Pattern.compile("\\b[a-z]+[A-Z][A-Za-z0-9]*\\b");

    /** 画布设计工具: 必填参数的描述里举驼峰字段代码就是它的格式要求。 */
    private static final String CANVAS_PREFIX = "canvas_";
    /** 可选参数里驼峰字段名条数的上限 (2026-10-06 修后实测 29), 只许减。 */
    private static final int OPTIONAL_CAMEL_CEILING = 29;

    private record Finding(String tool, String param, boolean required, String rule, String text) {
        @Override
        public String toString() {
            return tool + "." + param + (required ? " [必填|" : " [可选|") + rule + "]: " + text;
        }
    }

    private record ScanResult(int toolClasses, int scanned, int paramsWithDescription,
                              List<Finding> findings, List<String> unscannable) {
    }

    // ==================== 真实工具扫描 ====================

    @Test
    @DisplayName("硬闸: 没有任何参数描述含 UUID 或类型标注")
    void noUuidOrTypeAnnotationsInAnyParameterDescription() throws Exception {
        ScanResult r = scanRegisteredTools();
        assertThat(r.unscannable()).as("读不出 schema 的工具 (仪器盲区, 必须为 0)").isEmpty();
        assertThat(r.paramsWithDescription()).as("扫到的带描述参数数 (仪器活着)").isGreaterThan(1000);

        List<Finding> uuid = r.findings().stream().filter(f -> f.rule().equals("UUID")).toList();
        assertThat(uuid).as("参数描述里不许出现 UUID").isEmpty();

        List<Finding> typed = r.findings().stream()
                .filter(f -> f.rule().equals("类型标注")).toList();
        assertThat(typed).as("参数描述里不许出现 (Long) 等类型标注").isEmpty();
    }

    @Test
    @DisplayName("硬闸: 必填参数 (会被当成追问发给用户) 的描述不含驼峰字段名; 例外只有 canvas_* 设计工具")
    void requiredParameterDescriptionsHaveNoFieldNames() throws Exception {
        ScanResult r = scanRegisteredTools();
        List<Finding> leaks = r.findings().stream()
                .filter(f -> f.rule().equals("驼峰字段名") && f.required())
                .filter(f -> !f.tool().startsWith(CANVAS_PREFIX)).toList();
        assertThat(leaks).as("必填参数描述漏字段名").isEmpty();
        // 对照 (必发生): 豁免确实在起作用 —— canvas_* 里确有必填的驼峰例子, 否则这条豁免是死代码
        assertThat(r.findings().stream()
                .filter(f -> f.rule().equals("驼峰字段名") && f.required() && f.tool().startsWith(CANVAS_PREFIX))
                .count()).isGreaterThan(0);
    }

    @Test
    @DisplayName("棘轮: 可选参数描述里的驼峰字段名只许减不许增")
    void optionalParameterFieldNamesOnlyShrink() throws Exception {
        ScanResult r = scanRegisteredTools();
        long optionalCamel = r.findings().stream()
                .filter(f -> f.rule().equals("驼峰字段名") && !f.required()).count();
        assertThat(optionalCamel).as("可选参数描述里的驼峰字段名条数 (修了就把 OPTIONAL_CAMEL_CEILING 往下调)")
                .isLessThanOrEqualTo(OPTIONAL_CAMEL_CEILING);
    }

    // ==================== 判据本身 (阳性对照) ====================

    @Test
    @DisplayName("判据活着: 合成的脏 schema 被抓到, 干净的不被误抓")
    void scannerFlagsSyntheticLeaksAndSparesCleanText() {
        Map<String, Object> dirty = schema(List.of("a"),
                "a", "告警事件 ID (UUID)", "b", "工序任务ID (Long)", "c", "按 productTypeId 筛选");
        List<Finding> out = new ArrayList<>();
        scanSchema("t", dirty, out);
        assertThat(out).extracting(Finding::rule).contains("UUID", "类型标注", "驼峰字段名");
        assertThat(out).filteredOn(Finding::required).extracting(Finding::param).containsExactly("a");

        Map<String, Object> clean = schema(List.of("a"), "a", "告警事件编号", "b", "工序任务编号");
        List<Finding> none = new ArrayList<>();
        scanSchema("t", clean, none);
        assertThat(none).isEmpty();
    }

    // ==================== 扫描实现 ====================

    private ScanResult scanRegisteredTools() throws Exception {
        List<Class<?>> classes = toolClasses();
        List<Finding> findings = new ArrayList<>();
        List<String> unscannable = new ArrayList<>();
        ObjenesisStd objenesis = new ObjenesisStd();
        int scanned = 0;
        int[] params = {0};
        for (Class<?> c : classes) {
            try {
                AbstractBusinessTool tool = (AbstractBusinessTool) objenesis.newInstance(c);
                Map<String, Object> schema = tool.getParametersSchema();
                scanned++;
                params[0] += scanSchema(tool.getToolName(), schema, findings);
            } catch (Throwable t) {
                unscannable.add(c.getSimpleName() + ": " + t.getClass().getSimpleName());
            }
        }
        return new ScanResult(classes.size(), scanned, params[0], findings, unscannable);
    }

    private static int scanSchema(String tool, Map<String, Object> schema, List<Finding> out) {
        return walk(tool, schema, out, true);
    }

    @SuppressWarnings("unchecked")
    private static int walk(String tool, Map<String, Object> node, List<Finding> out, boolean topLevel) {
        int seen = 0;
        Object required = node.get("required");
        Object props = node.get("properties");
        if (props instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> def)) {
                    continue;
                }
                if (def.get("description") instanceof String s) {
                    seen++;
                    boolean isRequired = topLevel && required instanceof Collection<?> rc && rc.contains(e.getKey());
                    String param = String.valueOf(e.getKey());
                    if (UUID_WORD.matcher(s).find()) {
                        out.add(new Finding(tool, param, isRequired, "UUID", s));
                    }
                    if (TYPE_ANNOTATION.matcher(s).find()) {
                        out.add(new Finding(tool, param, isRequired, "类型标注", s));
                    }
                    if (CAMEL_CASE.matcher(s).find()) {
                        out.add(new Finding(tool, param, isRequired, "驼峰字段名", s));
                    }
                }
                seen += walk(tool, (Map<String, Object>) def, out, false);
            }
        }
        if (node.get("items") instanceof Map<?, ?> im) {
            seen += walk(tool, (Map<String, Object>) im, out, false);
        }
        return seen;
    }

    private static Map<String, Object> schema(List<String> required, String... kv) {
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            props.put(kv[i], Map.of("type", "string", "description", kv[i + 1]));
        }
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        return schema;
    }

    private List<Class<?>> toolClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AbstractBusinessTool.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cretas.aims")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (!java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                out.add(c);
            }
        }
        assertThat(out).as("扫描到的工具类数 (仪器活着)").hasSizeGreaterThan(400);
        return out;
    }
}
