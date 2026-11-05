package com.cretas.aims.service.secrecy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工厂级保密配置项 —— 唯一来源 (Steve 2026-11-03 裁定: 每项保密都由平台按工厂开 / 关)。
 *
 * <p>Python 与两个前端读同一份生成出来的 {@code secrecy-items.json} ({@link #toJsonModel()} 渲染,
 * {@code SecrecyItemsJsonDriftContractTest} 逐字节核对仓库里的每一份拷贝)。改这里的任何文字或字段都要重新生成:
 * {@code mvn test -Dtest=SecrecyItemsJsonDriftContractTest -Dsecrecy.items.write=true}。</p>
 *
 * <p>判定一律问 {@link FactorySecrecyConfig#isOn}: 按<b>数据或策略所属的工厂</b>判, 绝不按请求者 / 写入者的工厂判。
 * 读不到配置、工厂不明 (null / 空 / PLATFORM) 一律当开启 (偏向保密)。</p>
 *
 * <p>⛔ 跨厂隔离闸 (路径工厂 = 令牌工厂、查询自带 factory_id、缓存键带工厂…) 不读任何一项。</p>
 */
public enum SecrecyItem {

    PROCESS_SECRET("a", "工艺保密", Default.OFF, false, true, false, null, null,
            "关闭后，本厂的配方、配比、出成率和成本不再按人遮挡，本厂账号都能看到；AI 也不再按人裁剪这些数据；"
                    + "「工艺保密」设置页会隐藏。已经设好的保密产品和授权会保留，重新开启后恢复生效。"),

    PLATFORM_RECIPE_BLOCK("b", "平台看不到配方", Default.OFF, false, true, false, null, "PROCESS_SECRET",
            "开启时，平台人员不能自己查看、编辑配方和成本；关闭后可以，每一次都记入保密访问记录。"
                    + "只在「工艺保密」开启时起作用。"
                    + "这一项不影响平台授权的 AI 分析入口：它仍可分析数据，包括保密产品的成本，"
                    + "每次使用都记入保密访问记录；平台超级管理员不需要授权，其他平台账号须经授权。"),

    EGRESS_REDACTION("c", "出外脱敏", Default.OFF, false, true, false, null, null,
            "关闭后，发给外部 AI 模型的内容不再把公司名、人名、电话和产品名换成代号，按原文发出。"
                    + "不知道数据属于哪家工厂时，仍然不发。"),

    TRAINING_FILTER("d", "训练数据过滤", Default.OFF, false, false, false, null, null,
            "关闭后，保密产品名和工艺数值不再从训练样本中剔除；人名、客户名、工资考勤数字始终剔除，不受此项影响。"
                    + "能不能用于训练仍按本厂的训练授权（同意 / 撤回）决定。"),

    CUSTOMER_HELD("e", "客户侧保管（核心工艺不入云）", Default.OFF, false, true, true,
            "这会撤销与客户合同中约定的云端不保管工艺", null,
            "关闭后，设为「核心工艺不入云」的产品恢复为云端收发完整工艺。这一项与「工艺保密」互不影响："
                    + "开着时，即使工艺保密关闭，这些产品依然不在云端收发完整工艺。"),

    AI_SECRECY_AUDIT("f", "AI 保密审计", Default.FOLLOW_PROCESS_SECRET, true, false, false, null, null,
            "关闭后，不再为本厂每次 AI 请求记录保密访问档位。默认跟随「工艺保密」：工艺保密开着就记，关着就不记；"
                    + "也可以单独设为开或关。");

    /** 没有行时的默认值。 */
    public enum Default { OFF, FOLLOW_PROCESS_SECRET }

    private final String letter;
    private final String label;
    private final Default defaultValue;
    private final boolean triState;
    private final boolean requiresReason;
    private final boolean offRequiresConfirmation;
    private final String offConfirmWarning;
    private final String effectiveOnlyWhen;
    private final String offEffect;

    SecrecyItem(String letter, String label, Default defaultValue, boolean triState, boolean requiresReason,
                boolean offRequiresConfirmation, String offConfirmWarning, String effectiveOnlyWhen, String offEffect) {
        this.letter = letter;
        this.label = label;
        this.defaultValue = defaultValue;
        this.triState = triState;
        this.requiresReason = requiresReason;
        this.offRequiresConfirmation = offRequiresConfirmation;
        this.offConfirmWarning = offConfirmWarning;
        this.effectiveOnlyWhen = effectiveOnlyWhen;
        this.offEffect = offEffect;
    }

    public String letter() { return letter; }

    public String label() { return label; }

    public Default defaultValue() { return defaultValue; }

    /** 可以设为「跟随工艺保密」(只有 f)。 */
    public boolean triState() { return triState; }

    /** 改动必须填理由 (a / b / c / e; 服务端校验, 空白 = 400)。 */
    public boolean requiresReason() { return requiresReason; }

    /** 关闭时必须 confirmed=true (e)。 */
    public boolean offRequiresConfirmation() { return offRequiresConfirmation; }

    public String offConfirmWarning() { return offConfirmWarning; }

    /** 只在这一项开启时才起作用 (b 依赖 a); null = 独立。 */
    public String effectiveOnlyWhen() { return effectiveOnlyWhen; }

    /** 给不懂技术的读者看的「关闭后会怎样」。 */
    public String offEffect() { return offEffect; }

    public static Optional<SecrecyItem> fromCode(String code) {
        if (code == null) return Optional.empty();
        for (SecrecyItem item : values()) {
            if (item.name().equals(code.trim())) return Optional.of(item);
        }
        return Optional.empty();
    }

    /** 生成 {@code secrecy-items.json} 的模型 (字段顺序固定, 便于逐字节核对)。 */
    public static Map<String, Object> toJsonModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_generated", "由 Java 枚举 SecrecyItem 生成, 不要手改; 见 SecrecyItemsJsonDriftContractTest");
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (SecrecyItem item : values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", item.name());
            m.put("letter", item.letter);
            m.put("label", item.label);
            m.put("default", item.defaultValue.name());
            m.put("triState", item.triState);
            m.put("requiresReason", item.requiresReason);
            m.put("offRequiresConfirmation", item.offRequiresConfirmation);
            m.put("offConfirmWarning", item.offConfirmWarning);
            m.put("effectiveOnlyWhen", item.effectiveOnlyWhen);
            m.put("offEffect", item.offEffect);
            items.add(m);
        }
        root.put("items", items);
        return root;
    }
}
