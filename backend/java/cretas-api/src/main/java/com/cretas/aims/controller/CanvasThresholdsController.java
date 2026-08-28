package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas-Thresholds 阈值参数中心 (Phase A P0-1).
 *
 * <p>Per-factory threshold configuration overlaying hard-coded constants in 6 service classes
 * (Inventory / IoT / Credit / BOM / Shortage / AI). All endpoints scoped to {@code factoryId};
 * global default rows (factoryId = {@code "*"}) are managed via a dedicated admin path
 * {@code /api/mobile/&#42;/canvas-thresholds} — JwtAuthInterceptor treats {@code "*"} as a
 * factory-id placeholder same as any other string.
 *
 * <p>4-in-1 UX (per fool-proof Rule + qa-prompt v2.4): all error responses include
 * actionHint + severity + hintTarget when applicable.
 *
 * <h2>门禁 (2026-08-29 换掉硬编码角色白名单)</h2>
 * 读 {@code system:read} / 写 {@code system:read_write}，与
 * {@link FactorySettingsController} 的「工厂设置」写端点<b>同一个模型</b>。
 *
 * <p><b>换掉的是什么</b>: 原来是
 * {@code @RequireRole({"factory_super_admin","permission_admin","factory_admin"})} ——
 * 一份<b>手写的角色名清单</b>，它是权限矩阵的第二份副本，而副本一定会漂：
 * <ul>
 *   <li>{@code factory_admin} <b>根本不是</b> {@code FactoryUserRole} 的枚举值
 *       (2026-08-29 实测枚举 30 个值里没有它) ⇒ 那一项从写下那天起就是死条目</li>
 *   <li>餐饮租户老板 {@code restaurant_owner} 不在清单里 ⇒ <b>他配不了任何阈值</b>，
 *       尽管 L1 权限表 (V20261029_62) 明确给了他 {@code system='rw'}。
 *       新加一个角色要记得来改这里 —— 而没有任何东西会提醒你</li>
 * </ul>
 *
 * <p>⚠️ <b>三种注解不是一回事</b>，本次只换其中一种：
 * <ul>
 *   <li>{@code @RequirePermission("module:action")} —— 走 RBAC 权限矩阵
 *       (L2 工厂覆盖 → L1 平台默认 → 代码里的 fallback 矩阵)。<b>本类用它</b></li>
 *   <li>{@code @RequireModule("x")} —— 问的是<b>这家工厂启没启用这个模块</b>
 *       (租户功能开关)，与「这个人有没有权限」正交。阈值中心不是可关闭的业务模块，
 *       ⛔ 不加</li>
 *   <li>{@code @RequireRole({...})} —— 角色名白名单，就是本次换掉的那个</li>
 * </ul>
 *
 * @since Canvas Phase A (2026-05-21)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-thresholds")
@Tag(name = "Canvas-Thresholds 阈值参数中心",
     description = "工厂级阈值配置 — 库存 / IoT / 信用 / BOM / 缺料 / AI 路由 6 大类 ~19 阈值")
@RequiredArgsConstructor
public class CanvasThresholdsController {

    private static final int KEY_MAX_LENGTH = 100;
    private static final int VALUE_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final int UNIT_MAX_LENGTH = 20;

    private final FactoryThresholdRepository repository;
    private final ThresholdResolverService resolver;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂的所有阈值 (可按 category 过滤)")
    @RequirePermission("system:read")
    public ApiResponse<List<FactoryThreshold>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String category) {
        ThresholdCategory cat = (category != null && !category.isBlank()) ? parseCategory(category) : null;
        List<FactoryThreshold> own = cat != null
                ? repository.findByFactoryIdAndCategoryOrderByThresholdKey(factoryId, cat)
                : repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc(factoryId);
        return ApiResponse.success("查询成功", overlayGlobalDefaults(factoryId, cat, own));
    }

    /**
     * 把全局 {@code '*'} 默认值叠加进工厂视图 —— 返回的是<b>有效配置</b>，不是「这张表里属于我的行」。
     *
     * <h3>它修的是什么 (2026-08-29 生产实测)</h3>
     * 原实现严格按 {@code factory_id} 过滤，于是全局行在任何工厂的配置页上都<b>看不见</b>：
     * <ul>
     *   <li>{@code sales.delivery.over_ship_rate} 只 seed 了全局行 ⇒ <b>所有工厂都配不了它</b>
     *       —— 一个专门为「让它可配」而建的页面，恰恰配不了它</li>
     *   <li>{@code LIUSHANMEN}(真实客户) / {@code MOCK_REST} 各 <b>0 行</b> ⇒ 管理员看到 15 个
     *       空分类，而解析器一直在用全局默认值跑。<b>界面上的「什么都没有」与实际生效的值完全脱节。</b></li>
     * </ul>
     *
     * <p>⇒ 这里的梯子与 {@link com.cretas.aims.service.canvas.ThresholdResolverService} 一致：
     * per-factory 优先，缺失时回落全局。⛔ 两处梯子必须同源，否则「页面上显示的」和
     * 「运行时用的」会漂成两个东西 —— 那比看不见更危险。
     *
     * <p>⚠️ 继承来的行带 {@code inherited=true}，其 {@code id} 属于全局那一行。
     * 前端<b>必须</b>对它走 POST 建 override，⛔ 不能 PUT（那会改掉全局默认，影响所有工厂）。
     *
     * @param factoryId 路径上的工厂；等于 {@value FactoryThreshold#GLOBAL_FALLBACK_FACTORY_ID}
     *                  时表示正在直接管理全局行，此时<b>不</b>做叠加（否则自己叠自己）
     */
    private List<FactoryThreshold> overlayGlobalDefaults(String factoryId,
            ThresholdCategory cat, List<FactoryThreshold> own) {
        List<FactoryThreshold> globals = cat != null
                ? repository.findByFactoryIdAndCategoryOrderByThresholdKey(
                        FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID, cat)
                : repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc(
                        FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID);
        return overlay(factoryId, own, globals);
    }

    /**
     * 叠加梯子<b>本体</b> —— per-factory 优先，缺失的 key 带出全局那一行并标 {@code inherited=true}。
     *
     * <p>⚠️ 这是<b>唯一</b>一份叠加实现。{@link #list} 和 {@link #getByKey} 都走它，
     * 各自只负责「拿哪些行来叠」（整类 / 单个 key）。
     * ⛔ 不要在任何一条路径上再写一遍这个梯子 —— 两份一定会漂，而漂的症状是
     * 「列表里看得见、点进去 404」这种<b>自相矛盾但都不报错</b>的样子
     * （2026-08-29 之前 {@code getByKey} 就正是这样：{@code list()} 已经叠加了，
     * 它还在做精确匹配 ⇒ 继承场景下点开就是 404）。
     *
     * @param factoryId 等于 {@value FactoryThreshold#GLOBAL_FALLBACK_FACTORY_ID} 时表示正在
     *                  直接管理全局行，此时<b>不</b>叠加（否则自己叠自己）
     */
    private List<FactoryThreshold> overlay(String factoryId,
            List<FactoryThreshold> own, List<FactoryThreshold> globals) {
        if (FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID.equals(factoryId)) {
            // 管理全局路径本身: own 就是全局行, 它是本体不是继承来的。
            return new java.util.ArrayList<>(own);
        }

        java.util.Set<String> overridden = own.stream()
                .map(FactoryThreshold::getThresholdKey)
                .collect(java.util.stream.Collectors.toSet());

        List<FactoryThreshold> merged = new java.util.ArrayList<>(own);
        for (FactoryThreshold g : globals) {
            if (overridden.contains(g.getThresholdKey())) {
                continue;   // 工厂自己有 override, 全局那条不显示 —— 显示两条会让人不知道哪条在生效
            }
            g.setInherited(Boolean.TRUE);
            merged.add(g);
        }
        merged.sort(java.util.Comparator
                .comparing((FactoryThreshold t) -> t.getCategory() == null ? "" : t.getCategory().name())
                .thenComparing(t -> t.getThresholdKey() == null ? "" : t.getThresholdKey()));
        return merged;
    }

    /**
     * 查询单个阈值 —— 与 {@link #list} <b>同一条梯子</b>: per-factory 优先，缺失时回落全局。
     *
     * <h3>它修的是什么 (2026-08-29)</h3>
     * #315 把 {@code list()} 改成返回「有效配置」之后，本端点仍是
     * {@code findByFactoryIdAndThresholdKey(factoryId, key)} 的<b>精确</b>查询。
     * 于是同一个 key 在同一个工厂下有两个互相矛盾的答案：
     * 列表里带着 {@code inherited=true} <b>显示出来了</b>，点进去 <b>404「阈值不存在」</b>。
     *
     * <p>⚠️ 两处都不报错，两处各自看都"对"—— 只有把它们并排放才看得出来。
     *
     * <p>⛔ 修法是让它<b>复用</b> {@link #overlay}，不是在这里再抄一份回落逻辑。
     *
     * <p>⚠️ 与 {@code list()} 同样的前端约定: 返回 {@code inherited=true} 时，
     * 那个 {@code id} 属于全局行，保存必须走 POST 建 override，⛔ 不能 PUT。
     *
     * <p>真 404 的判据也随之收紧: <b>per-factory 和全局都没有</b>才是 404。
     */
    @GetMapping("/{key}")
    @Operation(summary = "查询单个阈值 (按 thresholdKey; per-factory 缺失时回落全局默认)")
    @RequirePermission("system:read")
    public ApiResponse<FactoryThreshold> getByKey(
            @PathVariable String factoryId,
            @PathVariable String key) {
        List<FactoryThreshold> own = asList(repository.findByFactoryIdAndThresholdKey(factoryId, key));
        List<FactoryThreshold> globals = asList(repository.findByFactoryIdAndThresholdKey(
                FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID, key));

        List<FactoryThreshold> merged = overlay(factoryId, own, globals);
        if (merged.isEmpty()) {
            throw new BusinessException(404, "阈值不存在: " + key)
                    .withHint("该 key 在本工厂和全局默认里都没有 —— 请先创建, 或确认 key 拼写正确")
                    .withSeverity("warning")
                    .withHintTarget("thresholdKey");
        }
        return ApiResponse.success("查询成功", merged.get(0));
    }

    /** {@code Optional} → 0/1 元素的 List，让单 key 路径能喂给同一个 {@link #overlay}。 */
    private static List<FactoryThreshold> asList(Optional<FactoryThreshold> opt) {
        return opt.map(List::of).orElseGet(List::of);
    }

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "创建阈值 (新增 per-factory 配置)")
    @RequirePermission("system:read_write")
    @Transactional
    public ApiResponse<FactoryThreshold> create(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        String thresholdKey = required(body, "thresholdKey");
        String category = required(body, "category");
        String valueType = required(body, "valueType");
        String thresholdValue = required(body, "thresholdValue");
        String defaultValue = required(body, "defaultValue");

        validateLengths(thresholdKey, thresholdValue, body.get("description"), body.get("unit"));

        // 防呆 (Rule 4): 唯一性 check 避免重复
        Optional<FactoryThreshold> dup = repository.findByFactoryIdAndThresholdKey(factoryId, thresholdKey);
        if (dup.isPresent()) {
            throw new BusinessException(409, "阈值已存在: " + thresholdKey
                    + " (id=" + dup.get().getId() + ")")
                    .withHint("请使用 PUT 更新现有阈值, 或修改 thresholdKey")
                    .withSeverity("warning")
                    .withHintTarget("thresholdKey");
        }

        ThresholdCategory cat = parseCategory(category);
        ThresholdValueType type = parseValueType(valueType);
        validateValueAgainstType(thresholdValue, type, "thresholdValue");
        validateValueAgainstType(defaultValue, type, "defaultValue");

        FactoryThreshold t = FactoryThreshold.builder()
                .factoryId(factoryId)
                .thresholdKey(thresholdKey)
                .category(cat)
                .valueType(type)
                .thresholdValue(thresholdValue)
                .defaultValue(defaultValue)
                .description(asString(body.get("description")))
                .unit(asString(body.get("unit")))
                .minValue(asString(body.get("minValue")))
                .maxValue(asString(body.get("maxValue")))
                .enabled(asBoolean(body.get("enabled"), true))
                .build();
        FactoryThreshold saved = repository.save(t);
        resolver.invalidate(factoryId, thresholdKey);
        log.info("create threshold: factoryId={}, key={}, value={}", factoryId, thresholdKey, thresholdValue);
        return ApiResponse.success("阈值已创建", saved);
    }

    // ==================== Update (PATCH semantics — Map body) ====================

    @PutMapping("/{id}")
    @Operation(summary = "修改阈值 (partial PATCH, Map body)")
    @RequirePermission("system:read_write")
    @Transactional
    public ApiResponse<FactoryThreshold> update(
            @PathVariable String factoryId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        FactoryThreshold t = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "阈值不存在: " + id));
        if (!factoryId.equals(t.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的阈值")
                    .withSeverity("warning");
        }

        // AUD-4 P1 (mirror ScheduledTaskController.checkVersion):
        // explicit optimistic-lock check fires BEFORE save so JPA can observe stale snapshots.
        // Null = lenient (legacy clients keep working).
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(t.getVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + t.getVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        // PATCH semantics: only apply fields present in body.
        if (body.containsKey("thresholdValue")) {
            String newValue = required(body, "thresholdValue");
            if (newValue.length() > VALUE_MAX_LENGTH) {
                throw new BusinessException(400,
                        "阈值最长 " + VALUE_MAX_LENGTH + " 字符 (当前 " + newValue.length() + ")")
                        .withHint("请使用更短的值")
                        .withSeverity("warning")
                        .withHintTarget("thresholdValue");
            }
            validateValueAgainstType(newValue, t.getValueType(), "thresholdValue");
            t.setThresholdValue(newValue);
        }
        if (body.containsKey("defaultValue")) {
            String newDefault = required(body, "defaultValue");
            validateValueAgainstType(newDefault, t.getValueType(), "defaultValue");
            t.setDefaultValue(newDefault);
        }
        if (body.containsKey("description")) {
            String desc = asString(body.get("description"));
            if (desc != null && desc.length() > DESCRIPTION_MAX_LENGTH) {
                throw new BusinessException(400,
                        "描述最长 " + DESCRIPTION_MAX_LENGTH + " 字符 (当前 " + desc.length() + ")")
                        .withHint("请精简描述")
                        .withSeverity("warning")
                        .withHintTarget("description");
            }
            t.setDescription(desc);
        }
        if (body.containsKey("unit")) {
            String unit = asString(body.get("unit"));
            if (unit != null && unit.length() > UNIT_MAX_LENGTH) {
                throw new BusinessException(400,
                        "单位最长 " + UNIT_MAX_LENGTH + " 字符 (当前 " + unit.length() + ")")
                        .withHint("请简化单位")
                        .withSeverity("warning")
                        .withHintTarget("unit");
            }
            t.setUnit(unit);
        }
        if (body.containsKey("minValue")) {
            t.setMinValue(asString(body.get("minValue")));
        }
        if (body.containsKey("maxValue")) {
            t.setMaxValue(asString(body.get("maxValue")));
        }
        if (body.containsKey("enabled")) {
            t.setEnabled(asBoolean(body.get("enabled"), true));
        }
        FactoryThreshold saved = repository.saveAndFlush(t);
        resolver.invalidate(factoryId, t.getThresholdKey());
        log.info("update threshold: factoryId={}, id={}, key={}, newValue={}",
                factoryId, id, t.getThresholdKey(), t.getThresholdValue());
        return ApiResponse.success("阈值已更新", saved);
    }

    // ==================== Delete (soft) ====================

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除阈值")
    @RequirePermission("system:read_write")
    @Transactional
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable UUID id) {
        FactoryThreshold t = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "阈值不存在: " + id));
        if (!factoryId.equals(t.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的阈值")
                    .withSeverity("warning");
        }
        t.softDelete();
        repository.save(t);
        resolver.invalidate(factoryId, t.getThresholdKey());
        log.info("delete threshold: factoryId={}, id={}, key={}", factoryId, id, t.getThresholdKey());
        return ApiResponse.success("阈值已删除", null);
    }

    // ==================== Reset-to-default ====================

    @PostMapping("/reset-to-default")
    @Operation(summary = "按类别批量重置阈值到默认值")
    @RequirePermission("system:read_write")
    @Transactional
    public ApiResponse<Integer> resetCategoryToDefault(
            @PathVariable String factoryId,
            @RequestParam String category) {
        ThresholdCategory cat = parseCategory(category);
        List<FactoryThreshold> rows = repository
                .findByFactoryIdAndCategoryOrderByThresholdKey(factoryId, cat);
        int updated = 0;
        for (FactoryThreshold t : rows) {
            if (!Objects.equals(t.getThresholdValue(), t.getDefaultValue())) {
                t.setThresholdValue(t.getDefaultValue());
                repository.save(t);
                resolver.invalidate(factoryId, t.getThresholdKey());
                updated++;
            }
        }
        log.info("reset-to-default: factoryId={}, category={}, updated={}", factoryId, category, updated);
        return ApiResponse.success(
                "已重置 " + updated + " 个阈值到默认值 (category=" + category + ")", updated);
    }

    // ==================== Helpers ====================

    private static String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new BusinessException(400, "字段不能为空: " + key)
                    .withSeverity("warning")
                    .withHintTarget(key);
        }
        return v.toString().trim();
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Long asLong(Object v, Long defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static ThresholdCategory parseCategory(String s) {
        try {
            return ThresholdCategory.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400,
                    "无效的 category: " + s
                            + " (有效值: INVENTORY, IOT, CREDIT, BOM, SHORTAGE, AI)")
                    .withHint("请选择 6 种类型之一")
                    .withSeverity("warning")
                    .withHintTarget("category");
        }
    }

    private static ThresholdValueType parseValueType(String s) {
        try {
            return ThresholdValueType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400,
                    "无效的 valueType: " + s
                            + " (有效值: INTEGER, DECIMAL, DOUBLE, STRING)")
                    .withHint("请选择 4 种类型之一")
                    .withSeverity("warning")
                    .withHintTarget("valueType");
        }
    }

    /**
     * 字段长度预校验 (AUD-5 B-A3 sister sweep): 在请求触底 PG VARCHAR 截断之前抛 400.
     * 未传入值的字段 (null) 跳过 — PATCH 语义。
     */
    private static void validateLengths(String key, String value, Object description, Object unit) {
        if (key != null && key.length() > KEY_MAX_LENGTH) {
            throw new BusinessException(400,
                    "thresholdKey 最长 " + KEY_MAX_LENGTH + " 字符 (当前 " + key.length() + ")")
                    .withHint("请使用更短的 key")
                    .withSeverity("warning")
                    .withHintTarget("thresholdKey");
        }
        if (value != null && value.length() > VALUE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "thresholdValue 最长 " + VALUE_MAX_LENGTH + " 字符 (当前 " + value.length() + ")")
                    .withHint("请使用更短的值")
                    .withSeverity("warning")
                    .withHintTarget("thresholdValue");
        }
        if (description != null && description.toString().length() > DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException(400,
                    "description 最长 " + DESCRIPTION_MAX_LENGTH + " 字符 (当前 "
                            + description.toString().length() + ")")
                    .withHint("请精简描述")
                    .withSeverity("warning")
                    .withHintTarget("description");
        }
        if (unit != null && unit.toString().length() > UNIT_MAX_LENGTH) {
            throw new BusinessException(400,
                    "unit 最长 " + UNIT_MAX_LENGTH + " 字符 (当前 " + unit.toString().length() + ")")
                    .withHint("请简化单位")
                    .withSeverity("warning")
                    .withHintTarget("unit");
        }
    }

    /**
     * 值类型预校验: thresholdValue 必须可被 valueType 解析, 否则抛 400.
     * 防止存入"NaN" / "abc" 这类 service 层 parse 时崩溃的值。
     */
    private static void validateValueAgainstType(String value, ThresholdValueType type, String fieldName) {
        if (value == null) return;
        try {
            switch (type) {
                case INTEGER -> Integer.parseInt(value);
                case DECIMAL -> new BigDecimal(value);
                case DOUBLE -> Double.parseDouble(value);
                case STRING -> { /* 任意字符串都合法 */ }
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    fieldName + " (" + value + ") 不能解析为 " + type)
                    .withHint("请检查值的格式")
                    .withSeverity("warning")
                    .withHintTarget(fieldName);
        }
    }
}
