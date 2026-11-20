package com.cretas.aims.util;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.discipline.FactoryDisciplinePolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 补录时效锁 — 防呆硬规则 (C-074/C-075/X-10, 六扇门客户原话"大前天不允许补")。
 *
 * <p>业务日期补录窗口: 默认 T(今天) / T-1(昨天) / T-2(前天) 允许补录; T-3 及更早一律拒绝。
 *
 * <p><b>窗口天数是工厂级配置</b> (纪律排查 A14, Steve 2026-11-10): 阈值中心
 * {@code production.backdate.max_days} (工厂行 &gt; 全局 {@code '*'} 行, 默认 2), 读不到时才用全局属性
 * {@code cretas.backdate.max-days}。所以每个调用方<b>必须</b>带 factoryId —— 故意没有不带 factoryId 的重载,
 * 否则新入口会悄悄走全局口径。
 *
 * <p>使用方 (报工 / 领料 / 班组报工 / 报工单提交与修改 / 原料入库, 全部走这一个方法):
 * <pre>
 *   backdateWindowValidator.assertWithinWindow(factoryId, businessDate, "补录入库");
 * </pre>
 *
 * <p>拒绝行为 (防呆 4 位一体):
 * <ul>
 *   <li>HTTP 409 + errorCode {@code BACKDATE_WINDOW_EXCEEDED}</li>
 *   <li>message 含具体日期与最早可补边界</li>
 *   <li>actionHint 告知联系主管处理</li>
 * </ul>
 *
 * <p>日期为 null / 今天 / 未来 → 不拦截 (未来日期合法性不属本 validator scope)。
 */
@Component
public class BackdateWindowValidator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 最多允许补录的天数: 2 = T-2(前天); 1 = T-1(昨天); 0 = 仅今天。
     * 配置键: {@code cretas.backdate.max-days}, 默认 2。
     */
    @Value("${cretas.backdate.max-days:2}")
    private int maxDays;

    /** 工厂级口径; 为空仅在不起 Spring 的单测里 (此时用 maxDays)。 */
    @Autowired(required = false)
    private FactoryDisciplinePolicy disciplinePolicy;

    /**
     * 断言 businessDate 在允许的补录窗口内。
     *
     * @param businessDate 业务日期 (可为 null; null = 不校验, 走当日逻辑)
     * @param context      错误信息中的业务场景说明, 如 "报工" / "原料入库"
     * @throws BusinessException 409 BACKDATE_WINDOW_EXCEEDED 当日期超出窗口
     */
    public void assertWithinWindow(String factoryId, LocalDate businessDate, String context) {
        assertWithinWindow(factoryId, businessDate, context, LocalDate.now());
    }

    /**
     * 可注入 today 的测试友好重载 (生产代码请用 assertWithinWindow(date, context))。
     */
    public void assertWithinWindow(String factoryId, LocalDate businessDate, String context, LocalDate today) {
        if (businessDate == null) {
            return; // null = 系统取当天, 不拦
        }
        if (!businessDate.isBefore(today)) {
            return; // 今天或未来 — 不拦 (未来合法性不属本 scope)
        }
        int days = maxDaysFor(factoryId);
        LocalDate earliest = today.minusDays(days);
        if (businessDate.isBefore(earliest)) {
            String dateStr = businessDate.format(DATE_FMT);
            String earliestStr = earliest.format(DATE_FMT);
            throw new BusinessException(409,
                    context + "业务日期 " + dateStr + " 超出补录窗口"
                            + "（本厂补录窗口 " + days + " 天，最早可补 " + earliestStr + "）"
                            + "，请联系主管处理")
                    .withCode("BACKDATE_WINDOW_EXCEEDED")
                    .withHint("补录窗口由本厂在「系统设置 → 业务阈值配置 → 生产」里设置 (production.backdate.max_days)；如需补录更早日期，请联系主管")
                    .withHintTarget("businessDate");
        }
    }

    /** 该工厂当前生效的补录窗口天数。 */
    public int maxDaysFor(String factoryId) {
        return disciplinePolicy != null ? disciplinePolicy.backdateMaxDays(factoryId, maxDays) : maxDays;
    }

    /** 仅供测试覆盖: 获取全局属性兜底的 maxDays 值。 */
    public int getMaxDays() {
        return maxDays;
    }
}
