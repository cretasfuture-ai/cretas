package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.entity.enums.FactoryUserRole;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建厂访谈里「要开哪些告警」的目录 —— 访谈问项 / 预览 / 落库<b>共用这一份</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * 2026-08-11 实测: {@code alert_rules} 与 {@code alert_thresholds} <b>全局 0 行</b>
 * (449 张表的库, 含 F006 这种真配过的工厂)。而告警子系统本身是完好的 ——
 * SpEL 求值、去重、通知派发、严重度覆盖全都实现了。
 *
 * <p>问题出在入口: 无论事件驱动还是定时兜底, 最后都汇进
 * {@code AlertEngineServiceImpl#triggerAlert}, 它的第一件事是
 * <pre>{@code
 * List<AlertRule> rules = ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(factoryId, type);
 * if (rules.isEmpty()) { return Collections.emptyList(); }
 * }</pre>
 * <b>0 行 = 两条路一起短路</b>, 整个系统一条告警都产生不了。
 * 唯一会自己建规则的是 {@code RestaurantHealthAlertBridgeService}(餐饮体检),
 * 其它 8 个类型从来没有人写过。
 *
 * <h2>⛔ 为什么金额类默认不开</h2>
 *
 * {@code triggerConditionSpel} 为空时 {@code evaluateRule} 直接返回 true ——
 * 「没有 SpEL = 用该类型的内置逻辑」。而 PO/SO 两个金额类的 listener
 * <b>内置逻辑里根本没有金额比较</b>: 它把 amount 放进 context 就无条件调
 * {@code triggerAlert}, 消息还写死「金额 ¥X 超过预警阈值」。
 * 所以不带阈值就开 = <b>每一张采购/销售单都收到一条「超过阈值」告警</b>。
 * 这类必须拿到金额、由我们拼出 {@code #amount >= X} 才允许开。
 *
 * <h2>角色码用枚举不用字符串</h2>
 *
 * {@code notifyRoles} 最终交给 {@code notifyRole(factoryId, roleCode, ...)} 按角色找人,
 * 拼错的角色码不会报错, 只是<b>通知没有人收得到</b> —— 又一处静默空转。
 * 所以这里引用 {@link FactoryUserRole} 常量, 打错字编译期就挂。
 */
public final class InterviewAlertCatalog {

    private InterviewAlertCatalog() {
    }

    /**
     * 不由建厂访谈提供的类型 —— 必须显式列出, 不能靠「目录里没有」来表达。
     *
     * <p>{@link AlertType#RESTAURANT_HEALTH_CHECK} 由
     * {@code RestaurantHealthAlertBridgeService#ensureDefaultRule} 在首次体检时自建,
     * 建厂时提前建反而会和它的 ruleName 幂等键打架。
     */
    public static final Set<AlertType> BRIDGE_PROVISIONED =
            Collections.unmodifiableSet(EnumSet.of(AlertType.RESTAURANT_HEALTH_CHECK));

    /**
     * 一个可选告警。
     *
     * @param needsAmount true = 必须带金额阈值才允许开(见类注释), 由我们拼 SpEL
     */
    public record Entry(AlertType type,
                        String label,
                        String detail,
                        boolean needsAmount,
                        AlertSeverity severity,
                        List<FactoryUserRole> notifyRoles) {

        /** 落库用的规则名 —— 同时是幂等键的一半, 改它等于建新规则, 别改。 */
        public String ruleName() {
            return label;
        }

        public List<String> notifyRoleCodes() {
            return notifyRoles.stream().map(Enum::name).toList();
        }
    }

    private static final Map<AlertType, Entry> ENTRIES = buildEntries();

    private static Map<AlertType, Entry> buildEntries() {
        Map<AlertType, Entry> m = new EnumMap<>(AlertType.class);
        put(m, new Entry(AlertType.INVENTORY_LOW, "库存不足",
                "某原料低于它的安全库存时提醒(安全库存在物料建档里逐个填)",
                false, AlertSeverity.MID,
                List.of(FactoryUserRole.warehouse_manager, FactoryUserRole.factory_super_admin)));
        put(m, new Entry(AlertType.INVENTORY_EXPIRING, "库存临期",
                "批次到保质期前 7 天提醒(内置 7 天, 暂不可调)",
                false, AlertSeverity.MID,
                List.of(FactoryUserRole.warehouse_manager, FactoryUserRole.quality_manager)));
        put(m, new Entry(AlertType.QUALITY_ANOMALY, "质检异常",
                "质检判定不合格时提醒",
                false, AlertSeverity.HIGH,
                List.of(FactoryUserRole.quality_manager, FactoryUserRole.factory_super_admin)));
        put(m, new Entry(AlertType.PO_AMOUNT_THRESHOLD, "大额采购单",
                "采购单金额达到设定值时提醒",
                true, AlertSeverity.MID,
                List.of(FactoryUserRole.procurement_manager, FactoryUserRole.finance_manager)));
        put(m, new Entry(AlertType.SO_AMOUNT_THRESHOLD, "大额销售单",
                "销售单金额达到设定值时提醒",
                true, AlertSeverity.MID,
                List.of(FactoryUserRole.sales_manager, FactoryUserRole.finance_manager)));
        put(m, new Entry(AlertType.SALES_DECLINE, "销售额下滑",
                "销售额环比明显下滑时提醒",
                false, AlertSeverity.MID,
                List.of(FactoryUserRole.sales_manager, FactoryUserRole.factory_super_admin)));
        put(m, new Entry(AlertType.CUSTOMER_PAYMENT_OVERDUE, "客户回款逾期",
                "客户应收超期未回款时提醒",
                false, AlertSeverity.HIGH,
                List.of(FactoryUserRole.finance_manager, FactoryUserRole.sales_manager)));
        put(m, new Entry(AlertType.SUPPLIER_PAYABLE_DUE, "供应商付款到期",
                "应付供应商货款临近到期时提醒",
                false, AlertSeverity.MID,
                List.of(FactoryUserRole.finance_manager, FactoryUserRole.procurement_manager)));
        return Collections.unmodifiableMap(m);
    }

    private static void put(Map<AlertType, Entry> m, Entry e) {
        m.put(e.type(), e);
    }

    /**
     * ⛔ 只发站内信。SMS 要走 {@code recordSmsAudit} 且真的发短信(有成本),
     * 建厂时默认替客户开短信是替他花钱 —— 想要短信让他自己在告警设置里加。
     */
    public static final List<String> DEFAULT_NOTIFY_CHANNELS = List.of("IN_APP");

    public static List<Entry> all() {
        return List.copyOf(ENTRIES.values());
    }

    public static Entry find(String alertTypeName) {
        if (alertTypeName == null) {
            return null;
        }
        try {
            return ENTRIES.get(AlertType.valueOf(alertTypeName.trim()));
        } catch (IllegalArgumentException notAnAlertType) {
            return null;
        }
    }

    /**
     * 金额阈值 → SpEL。
     *
     * <p>用 {@code #amount} 而不是 {@code #context.amount}: 两个 listener 都
     * {@code context.put("amount", ...)}(2026-08-11 实测), 而 {@code evaluateRule}
     * 会把 context 的顶层 key 也暴露成 {@code #} 变量。两种写法都能求值,
     * 短的那种给人看更清楚。
     */
    public static String amountSpel(java.math.BigDecimal threshold) {
        return "#amount >= " + threshold.toPlainString();
    }
}
