package com.cretas.aims.service.entity;

import com.cretas.aims.repository.FactoryEntityDirectoryRepository;
import com.cretas.aims.repository.intent.FlywheelMasterDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 实体解析公共能力 (INTENT-DISTILL-20261111 第③步 PR-5; 方案第 2 节第 1、3 层, 第 7 节「实体解析不完整」)。
 *
 * <p><b>口径 (Steve 定)</b>: 用户说的客户 / 产品 / 员工 / 物料 / 供应商, 只到<b>本厂</b>主数据里<b>规范化后精确匹配</b>,
 * ⛔ 不做包含匹配, ⛔ 不靠语义 / embedding 猜。结果三态: 唯一 / 多个 (每个带区分字段: 部门、工号、手机尾号、地址 …,
 * 口径规则 8 全部列出) / 无。</p>
 *
 * <p><b>规范化 ({@link #matchKey})</b>, 两边用同一份:
 * <ol>
 *   <li>NFKC (全角字母数字空格 → 半角);</li>
 *   <li>去掉所有空白;</li>
 *   <li>转小写;</li>
 *   <li>只对单位名 (客户 / 供应商): 去掉「有限公司 / 卤味店 / 店 …」后缀, 最多两层, 剩下的至少 {@value #MIN_CORE} 个字
 *       才去 (「老张家卤味店」与「老张家」相等; 「华联超市」不会变成「华联」)。</li>
 * </ol>
 *
 * <p><b>三个使用方, 同一份实现</b> (源码闸 {@code FactoryEntityResolverWiringSourceGateTest}):
 * 填参数 ({@code LlmParameterGrounding}), 同名列出 ({@code SameNameMatches} 的考勤 / CRM 工具), 飞轮脱敏的名单
 * ({@code FlywheelTextProtector} 用 {@link #redactionNames} + {@link #surfaceForms})。</p>
 *
 * <p>🔴 每次解析都必须带 factoryId; 仓储查询按 factory_id 取候选, 别的工厂的同名对象不会出现。</p>
 *
 * <p>🔴 解析本身不判权限: 把解析出的对象 (尤其员工的部门 / 工号、客户的地址) 给到用户之前, 调用方自己判
 * (考勤按 hr:read, 见 AttendanceSubject; CRM 按 ToolRbacEnforcer 的客户四码)。新的注入方须在
 * {@code FactoryEntityResolverWiringSourceGateTest#REGISTERED_CONSUMERS} 登记。</p>
 */
@Component
public class FactoryEntityResolver {

    /** 单位名后缀 (从长到短, 最多去两层)。脱敏的「称呼核心」与解析的规范化共用这一份。 */
    public static final List<String> ORGANIZATION_SUFFIXES = List.of(
            "股份有限公司", "有限责任公司", "有限公司", "公司", "集团", "卤味店", "熟食店", "餐饮店", "批发部", "门市部",
            "食品厂", "饭店", "酒楼", "酒店", "餐厅", "餐馆", "食堂", "超市", "商行", "商贸", "连锁", "卤味", "熟食",
            "餐饮", "店", "铺");
    /** 去后缀后至少剩这么多字才算一个称呼核心。 */
    public static final int MIN_CORE = 3;
    private static final int MAX_SUFFIX_ROUNDS = 2;
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000\\u00a0]+");

    private final FactoryEntityDirectoryRepository directory;
    private final FlywheelMasterDataRepository allNames;

    @Autowired
    public FactoryEntityResolver(FactoryEntityDirectoryRepository directory, FlywheelMasterDataRepository allNames) {
        this.directory = directory;
        this.allNames = allNames;
    }

    public enum Status { UNIQUE, MULTIPLE, NONE }

    /**
     * 一个命中。{@code distinguishing} 已拼好 (如「车间/部门：加工车间，工号 012」), 同名的两行一定能分开;
     * {@code fields} 是原始区分字段 (值可能为 null)。
     */
    public record Candidate(String id, String name, String distinguishing, Map<String, String> fields) {
    }

    /** 解析结果: {@code matches} 是本厂全部命中 (主数据顺序, 不截断)。 */
    public record Resolution(EntityKind kind, String query, List<Candidate> matches) {
        public Status status() {
            return matches.isEmpty() ? Status.NONE : matches.size() == 1 ? Status.UNIQUE : Status.MULTIPLE;
        }

        public Optional<Candidate> unique() {
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        }
    }

    /**
     * 本厂主数据里规范化后与 {@code name} 相等的全部对象。
     *
     * @throws IllegalArgumentException factoryId 为空 (实体解析不许不带工厂范围)
     */
    public Resolution resolve(String factoryId, EntityKind kind, String name) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("实体解析必须带 factoryId");
        }
        String query = name == null ? "" : name.trim();
        String key = matchKey(kind, query);
        if (key.isEmpty()) {
            return new Resolution(kind, query, List.of());
        }
        List<Candidate> matches = new ArrayList<>();
        for (Object[] row : rows(factoryId, kind)) {
            Candidate c = candidate(kind, row, key);
            if (c != null) {
                matches.add(c);
            }
        }
        return new Resolution(kind, query, Collections.unmodifiableList(matches));
    }

    /**
     * 脱敏名单用的本厂名字 (含已停用 / 软删的行, fail-closed)。查不出来照样抛 —— 调用方 (飞轮脱敏) 据此决定不留文本,
     * ⛔ 不在这里吞掉异常。员工不在此列 (脱敏的人名名单另含客户 / 供应商联系人, 见 FactoryNameDirectoryRepository)。
     */
    public List<String> redactionNames(String factoryId, EntityKind kind) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("脱敏名单必须带 factoryId");
        }
        return switch (kind) {
            case CUSTOMER -> allNames.findCustomerNames(factoryId);
            case SUPPLIER -> allNames.findSupplierNames(factoryId);
            case PRODUCT -> allNames.findProductNames(factoryId);
            case MATERIAL -> allNames.findIngredientNames(factoryId);
            case EMPLOYEE -> throw new IllegalArgumentException("员工姓名名单走 FactoryNameDirectoryRepository");
        };
    }

    // ------------------------------------------------------------------ 规范化 (唯一一份)

    /** 比较用的键: NFKC + 去空白 + 小写; 单位名再去后缀 (见类注释)。 */
    public static String matchKey(EntityKind kind, String name) {
        if (name == null) {
            return "";
        }
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC);
        n = WHITESPACE.matcher(n).replaceAll("").toLowerCase(Locale.ROOT);
        if (!kind.organization) {
            return n;
        }
        List<String> cores = cores(n);
        return cores.isEmpty() ? n : cores.get(cores.size() - 1);
    }

    /**
     * 名字在文本里可能出现的写法 (原字符, 不规范化): 全名; 单位名再加上去后缀后的称呼核心
     * (「老张家卤味店」→「老张家卤味店」「老张家」)。脱敏扫描文本用。
     */
    public static List<String> surfaceForms(EntityKind kind, String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String full = name.trim();
        List<String> out = new ArrayList<>();
        out.add(full);
        if (kind.organization) {
            out.addAll(cores(full));
        }
        return out;
    }

    /** 去掉一层单位后缀; 没有可去的 (或去完是空串) 返回原串。 */
    public static String stripSuffix(String s) {
        for (String suffix : ORGANIZATION_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                return s.substring(0, s.length() - suffix.length());
            }
        }
        return s;
    }

    /** 逐层去后缀 (最多两层), 留下每层里不短于 {@value #MIN_CORE} 字的核心, 由浅到深。 */
    private static List<String> cores(String full) {
        List<String> out = new ArrayList<>();
        String core = full;
        for (int round = 0; round < MAX_SUFFIX_ROUNDS; round++) {
            String stripped = stripSuffix(core);
            if (stripped.equals(core)) {
                break;
            }
            core = stripped;
            if (core.length() >= MIN_CORE) {
                out.add(core);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 候选

    private List<Object[]> rows(String factoryId, EntityKind kind) {
        List<Object[]> rows = switch (kind) {
            case CUSTOMER -> directory.findCustomerRows(factoryId);
            case SUPPLIER -> directory.findSupplierRows(factoryId);
            case PRODUCT -> directory.findProductRows(factoryId);
            case MATERIAL -> directory.findMaterialRows(factoryId);
            case EMPLOYEE -> directory.findActiveEmployeeRows(factoryId);
        };
        return rows == null ? List.of() : rows;
    }

    /** 这一行的名字 (供应商另看简称) 规范化后等于 key 才是命中; 否则 null。 */
    private static Candidate candidate(EntityKind kind, Object[] row, String key) {
        String id = str(row, 0);
        String name = str(row, 1);
        boolean hit = key.equals(matchKey(kind, name));
        if (!hit && kind == EntityKind.SUPPLIER) {
            hit = key.equals(matchKey(kind, str(row, 2)));
        }
        if (!hit) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        switch (kind) {
            case EMPLOYEE -> {
                fields.put("department", str(row, 2));
                fields.put("position", str(row, 3));
                fields.put("employeeCode", str(row, 4));
                add(parts, "车间/部门：", str(row, 2));
                add(parts, "岗位：", str(row, 3));
                add(parts, "工号 ", str(row, 4));
                if (parts.isEmpty()) {
                    add(parts, "手机尾号 ", tail(str(row, 5)));
                }
                if (parts.isEmpty()) {
                    parts.add("用户ID " + id);
                }
            }
            case CUSTOMER -> {
                fields.put("customerCode", str(row, 2));
                fields.put("address", str(row, 3));
                String phone = firstNonBlank(str(row, 4), str(row, 5));
                fields.put("phoneTail", tail(phone));
                parts.add(blank(str(row, 2)) ? "客户ID " + id : "客户编码 " + str(row, 2));
                add(parts, "地址：", str(row, 3));
                add(parts, "电话尾号 ", tail(phone));
            }
            case SUPPLIER -> {
                fields.put("shortName", str(row, 2));
                fields.put("supplierCode", str(row, 3));
                fields.put("address", str(row, 4));
                String phone = firstNonBlank(str(row, 5), str(row, 6));
                fields.put("phoneTail", tail(phone));
                parts.add(blank(str(row, 3)) ? "供应商ID " + id : "供应商编码 " + str(row, 3));
                add(parts, "地址：", str(row, 4));
                add(parts, "电话尾号 ", tail(phone));
            }
            case PRODUCT -> {
                fields.put("code", str(row, 2));
                fields.put("specification", str(row, 3));
                fields.put("category", str(row, 4));
                parts.add(blank(str(row, 2)) ? "产品ID " + id : "编码 " + str(row, 2));
                add(parts, "规格：", str(row, 3));
                add(parts, "分类：", str(row, 4));
            }
            case MATERIAL -> {
                fields.put("code", str(row, 2));
                fields.put("category", str(row, 3));
                fields.put("unit", str(row, 4));
                parts.add(blank(str(row, 2)) ? "物料ID " + id : "编码 " + str(row, 2));
                add(parts, "分类：", str(row, 3));
                add(parts, "单位：", str(row, 4));
            }
        }
        return new Candidate(id, name == null ? null : name.trim(), String.join("，", parts),
                Collections.unmodifiableMap(fields));
    }

    private static void add(List<String> parts, String label, String value) {
        if (!blank(value)) {
            parts.add(label + value.trim());
        }
    }

    private static String tail(String phone) {
        if (blank(phone)) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    private static String firstNonBlank(String a, String b) {
        return blank(a) ? b : a;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object[] row, int i) {
        return row == null || i >= row.length || row[i] == null ? null : String.valueOf(row[i]);
    }
}
