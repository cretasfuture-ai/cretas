package com.cretas.aims.service.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 指标数值可见性的唯一判定 —— web 指标中心 (IndicatorController /indicators、CanvasIndicatorsController
 * /canvas-indicators) 与 AI 指标工具 (indicator_query / indicator_comparison / indicator_alert) 共用。
 *
 * <p>金额类指标 ({@link #carriesMoneyFigure}) 的数值、告警级别 (breachLevel / alertLevel) 与阈值, 只给价格查看口径
 * ({@link #VALUE_SPEC}, 经 {@link PriceFieldResponseAdvice#showsSpec} 按权限配置判, 不看角色名, 不另要 finance:read_write)
 * 看得到的调用者; 告警级别与阈值会夹出数值区间, 所以与数值同进同出。损耗率、周转率、良品率这类运营比率不藏。
 */
public final class IndicatorValueVisibility {

    private IndicatorValueVisibility() {
    }

    /** 金额类指标的数值按这个价格口径给 (与 #1801 利润表同口径: 有价格查看就给)。 */
    public static final String VALUE_SPEC = PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION;

    public static final String VALUE_HIDDEN_MESSAGE =
            " 的数值、告警级别与阈值按当前账号的权限配置不显示（金额 / 毛利 / 财务类指标，与 web 指标中心同一判定，需要价格查看权限）";

    /** 货币单位: 元 (含 元/kg、万元、千元)、¥ ￥、CNY RMB USD $、万。 */
    private static final Pattern MONEY_UNIT = Pattern.compile("(?i).*(元|¥|￥|CNY|RMB|USD|\\$|万).*");

    /**
     * 响应层 Rule 2b 词表 ({@link PriceFieldResponseAdvice#looksLikeMoneyKey}) 没覆盖、但指标里常见的金额 / 毛利说法。
     * 只多藏不少藏: 认错了是多藏一个指标, 认漏了是泄漏。
     */
    private static final Pattern INDICATOR_MONEY_TERMS = Pattern.compile(
            "(?i).*(MARGIN|毛利|GMV|SALES|销售额|应收|应付|货值|价值|客单|TICKET|金额).*");

    /**
     * 这个指标的数值是不是金额 / 毛利 / 财务数字。只用指标已有的定义字段: 分类 FINANCE; 或单位是货币;
     * 或编码 / 名称命中金额词表 (菜品毛利率 RESTAURANT_DISH_MARGIN 的单位是 %, 只能靠编码 / 名称认出;
     * 管理员自建的「客单价」单位可能留空)。null ⇒ 当金额 (fail closed)。
     */
    public static boolean carriesMoneyFigure(Indicator i) {
        if (i == null) {
            return true;
        }
        if ("FINANCE".equalsIgnoreCase(i.getCategory())) {
            return true;
        }
        if (i.getUnit() != null && MONEY_UNIT.matcher(i.getUnit()).matches()) {
            return true;
        }
        return looksLikeMoney(i.getCode()) || looksLikeMoney(i.getName());
    }

    private static boolean looksLikeMoney(String text) {
        return text != null
                && (PriceFieldResponseAdvice.looksLikeMoneyKey(text) || INDICATOR_MONEY_TERMS.matcher(text).matches());
    }

    /**
     * 本次 HTTP 请求的调用者对每个指标看不看得到数值。非金额指标恒为 true; 金额指标按价格查看口径, 一个请求只问一次。
     * 判定器没装配 / 不在请求里 / 平台代看 / 取不到调用者 ⇒ 看不到 (fail closed, 与响应层同)。
     */
    public static Predicate<Indicator> forCurrentRequest(PriceFieldResponseAdvice priceView) {
        Boolean[] sees = new Boolean[1];
        return i -> {
            if (!carriesMoneyFigure(i)) {
                return true;
            }
            if (sees[0] == null) {
                sees[0] = callerSeesMoneyValues(priceView);
            }
            return sees[0];
        };
    }

    static boolean callerSeesMoneyValues(PriceFieldResponseAdvice priceView) {
        if (priceView == null) {
            return false;
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        HttpServletRequest request = attrs.getRequest();
        if (PlatformFactoryViewReadPolicy.isView(request)) {
            return false;
        }
        Object raw = request.getAttribute("userId");
        Long userId = raw instanceof Number n ? Long.valueOf(n.longValue())
                : raw instanceof String str && str.matches("\\d+") ? Long.valueOf(str) : null;
        return userId != null && priceView.showsSpec(userId, VALUE_SPEC);
    }
}
