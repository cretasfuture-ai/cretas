package com.cretas.aims.ai.catalog;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 写意图权限闸的两层真值 (测试共用, 不进生产):
 * <ol>
 *   <li><b>目录层</b>: {@code ai/factory-intent-catalog.json} 的启用意图, 按生产同一判定 —— 绑定工具
 *       {@link ToolAccessModes#resolve} 为 WRITE (工具未注册也按写, fail-closed), 或意图码命中
 *       {@link WriteGuardService#hasWriteSuffix} —— 得出「写意图」全集;</li>
 *   <li><b>迁移层</b>: {@code db/flyway} 里带标记 {@value #MARKER} 的迁移, 其中每个 {@code ('意图码', '权限码')}
 *       元组就是迁移跑完后该意图的 required_permission (迁移只补空值; 版本号大的覆盖小的)。</li>
 * </ol>
 */
public final class WriteIntentPermissionSources {

    public static final String MARKER = "-- @write-intent-required-permission";
    private static final Pattern TUPLE = Pattern.compile("\\(\\s*'([A-Z0-9_]+)'\\s*,\\s*'([^']*)'\\s*\\)");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)_(\\d+)__");

    private WriteIntentPermissionSources() {
    }

    public record CatalogIntent(String intentCode, String toolName, boolean active) {
    }

    /** 目录里全部规范意图 (意图码 → 行)。 */
    public static Map<String, CatalogIntent> catalogIntents() {
        try (InputStream in = WriteIntentPermissionSources.class.getResourceAsStream("/ai/factory-intent-catalog.json")) {
            if (in == null) throw new AssertionError("ai/factory-intent-catalog.json 不在 classpath 上");
            JsonNode doc = new ObjectMapper().readTree(in);
            Map<String, CatalogIntent> out = new LinkedHashMap<>();
            for (JsonNode r : doc.path("canonical_intents")) {
                String code = r.path("intent_code").asText();
                String tool = r.path("tool_name").isTextual() ? r.path("tool_name").asText() : null;
                out.put(code, new CatalogIntent(code, tool, r.path("is_active").asBoolean(false)));
            }
            if (out.isEmpty()) throw new AssertionError("目录里一个意图都没读到 —— 仪器坏了, 不是没有写意图");
            return out;
        } catch (IOException e) {
            throw new AssertionError("读不了 ai/factory-intent-catalog.json", e);
        }
    }

    /** classpath 上全部工具实现 (工具名 → 真实类的实例, 只调元数据方法)。 */
    public static Map<String, ToolExecutor> tools() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));
        Map<String, ToolExecutor> tools = new LinkedHashMap<>();
        for (BeanDefinition d : scanner.findCandidateComponents("com.cretas.aims.ai.tool.impl")) {
            Class<?> type;
            try {
                type = Class.forName(d.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError("工具类加载不了: " + d.getBeanClassName(), e);
            }
            if (Modifier.isAbstract(type.getModifiers()) || type.isInterface() || type.getEnclosingClass() != null) {
                continue;
            }
            ToolExecutor e = mock(type.asSubclass(ToolExecutor.class), withSettings().defaultAnswer(CALLS_REAL_METHODS));
            String name = e.getToolName();
            if (name != null && !name.isBlank()) tools.putIfAbsent(name, e);
        }
        return tools;
    }

    /** 目录层的启用写意图 (意图码 → 绑定工具名, 可为 null)。 */
    public static Map<String, String> enabledWriteIntents(Map<String, CatalogIntent> catalog, Map<String, ToolExecutor> tools) {
        WriteGuardService suffix = mock(WriteGuardService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        Map<String, String> out = new LinkedHashMap<>();
        for (CatalogIntent c : catalog.values()) {
            if (!c.active()) continue;
            boolean toolWrite = c.toolName() != null && ToolAccessModes.isWrite(tools.get(c.toolName()));
            if (toolWrite || suffix.hasWriteSuffix(c.intentCode())) out.put(c.intentCode(), c.toolName());
        }
        return out;
    }

    /** 迁移层: 带标记的迁移按版本号升序叠加, 得出每个意图跑完迁移后的 required_permission。 */
    public static Map<String, String> migratedPermissions() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Resource r : markedMigrations()) {
            Matcher m = TUPLE.matcher(read(r));
            while (m.find()) out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /** 带标记的迁移 (版本升序); 一个都没有 = 仪器坏了。 */
    public static List<Resource> markedMigrations() {
        try {
            Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath*:db/flyway/V*.sql");
            List<Resource> marked = Arrays.stream(all).filter(r -> read(r).contains(MARKER))
                    .sorted(Comparator.comparing(WriteIntentPermissionSources::versionKey)).toList();
            if (marked.isEmpty()) throw new AssertionError("db/flyway 里没有带 " + MARKER + " 的迁移 —— 仪器坏了");
            return marked;
        } catch (IOException e) {
            throw new AssertionError("扫描 db/flyway 失败", e);
        }
    }

    public static String read(Resource r) {
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读不了迁移 " + r.getFilename(), e);
        }
    }

    private static String versionKey(Resource r) {
        Matcher m = VERSION.matcher(r.getFilename() == null ? "" : r.getFilename());
        if (!m.find()) return "~" + r.getFilename();
        return String.format("%012d_%06d", Long.parseLong(m.group(1)), Long.parseLong(m.group(2)));
    }
}
