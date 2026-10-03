package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.execution.IntentPermissionGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 使用帮助工具
 *
 * <p>2026-10-03 (意图识别 90% 方案 第 1 步): 帮助内容改成「这个人现在真的能用的功能」——
 * 从当前工厂可见、启用、且这个角色有权限的意图里挑, 查询类在前, 会改数据的单列并说明「会先请你确认」。
 * 此前是一段写死的文字, 还列着尚未开放的设备管理。
 *
 * <p>取不到可用功能时如实说明, 不编一段示例出来。
 *
 * Intent Code: SYSTEM_HELP
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SystemHelpTool extends AbstractBusinessTool {

    /** 查询类最多列几条、会改数据的最多列几条: 帮助是给人看的, 不是目录。 */
    static final int MAX_READ = 8;
    static final int MAX_WRITE = 4;

    /** 不进帮助列表的: 帮助自己、翻页这种对话控制、系统/餐饮类。 */
    private static final Set<String> EXCLUDED_CATEGORIES = Set.of("SYSTEM", "RESTAURANT");
    private static final Set<String> EXCLUDED_BUSINESS_TYPES = Set.of("RESTAURANT");
    private static final Set<String> WRITE_SENSITIVITY = Set.of("HIGH", "MEDIUM", "CRITICAL");

    @Autowired
    @Lazy
    private AIIntentService aiIntentService;

    @Autowired(required = false)
    @Lazy
    private IntentPermissionGate intentPermissionGate;

    @Override
    public String getToolName() {
        return "system_help";
    }

    @Override
    public String getDescription() {
        return "提供系统使用帮助，按当前账号的权限列出能用的功能和说法示例。" +
                "适用场景：用户说'帮助'、'怎么用'、'有什么功能'等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String role = context == null ? null : asString(context.get("userRole"));
        Long userId = context == null ? null : asLong(context.get("userId"));
        log.info("使用帮助 - 工厂ID: {}, 角色: {}", factoryId, role);

        List<AIIntentConfig> usable = usableIntents(factoryId, userId, role);
        List<AIIntentConfig> reads = usable.stream().filter(i -> !isWrite(i)).limit(MAX_READ).toList();
        List<AIIntentConfig> writes = usable.stream().filter(SystemHelpTool::isWrite).limit(MAX_WRITE).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("message", render(reads, writes));
        result.put("queries", reads.stream().map(SystemHelpTool::entry).toList());
        result.put("operations", writes.stream().map(SystemHelpTool::entry).toList());
        result.put("availableCount", usable.size());
        return result;
    }

    /** 当前工厂可见、启用、角色与权限码都放行的意图, 按优先级从高到低。 */
    List<AIIntentConfig> usableIntents(String factoryId, Long userId, String role) {
        List<AIIntentConfig> all = aiIntentService.getAllIntents(factoryId);
        if (all == null) {
            return List.of();
        }
        return all.stream()
                .filter(i -> Boolean.TRUE.equals(i.getIsActive()) && i.getDeletedAt() == null)
                .filter(i -> !"SYSTEM_HELP".equals(i.getIntentCode()))
                .filter(i -> i.getIntentCode() != null && !i.getIntentCode().startsWith("PAGINATION_"))
                .filter(i -> !EXCLUDED_CATEGORIES.contains(upper(i.getIntentCategory())))
                .filter(i -> !EXCLUDED_BUSINESS_TYPES.contains(upper(i.getBusinessType())))
                .filter(i -> role == null || aiIntentService.hasPermission(factoryId, i.getIntentCode(), role))
                .filter(i -> intentPermissionGate == null
                        || !intentPermissionGate.check(i, userId, role).isDenied())
                .sorted(Comparator.comparing((AIIntentConfig i) -> i.getPriority() == null ? 0 : i.getPriority())
                        .reversed()
                        .thenComparing(AIIntentConfig::getIntentCode))
                .collect(Collectors.toList());
    }

    static String render(List<AIIntentConfig> reads, List<AIIntentConfig> writes) {
        if (reads.isEmpty() && writes.isEmpty()) {
            return "当前账号还没有可以在对话里使用的功能。如需开通，请联系管理员。";
        }
        StringBuilder sb = new StringBuilder();
        if (!reads.isEmpty()) {
            sb.append("您可以这样问我：\n");
            reads.forEach(i -> sb.append("- 「").append(example(i)).append("」").append(suffix(i)).append('\n'));
        }
        if (!writes.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("也可以让我帮您办（会先请您确认）：\n");
            writes.forEach(i -> sb.append("- 「").append(example(i)).append("」").append(suffix(i)).append('\n'));
        }
        sb.append("\n说得不够清楚时，我会给您几个选项，点一下就行。");
        return sb.toString();
    }

    private static Map<String, Object> entry(AIIntentConfig i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentCode", i.getIntentCode());
        m.put("name", i.getIntentName());
        m.put("example", example(i));
        return m;
    }

    /** 说法示例: 先用例句, 没有就用第一个常见说法, 都没有用功能名。 */
    static String example(AIIntentConfig i) {
        List<String> examples = i.getExampleQueriesList();
        if (examples != null) {
            for (String e : examples) {
                if (e != null && !e.isBlank()) {
                    return e.strip();
                }
            }
        }
        List<String> keywords = i.getKeywordsList();
        if (keywords != null) {
            for (String k : keywords) {
                if (k != null && !k.isBlank()) {
                    return k.strip();
                }
            }
        }
        return i.getIntentName() == null ? i.getIntentCode() : i.getIntentName();
    }

    private static String suffix(AIIntentConfig i) {
        String name = i.getIntentName();
        return name == null || name.isBlank() || name.equals(example(i)) ? "" : " —— " + name;
    }

    static boolean isWrite(AIIntentConfig i) {
        return WRITE_SENSITIVITY.contains(upper(i.getSensitivityLevel()));
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? null : Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
