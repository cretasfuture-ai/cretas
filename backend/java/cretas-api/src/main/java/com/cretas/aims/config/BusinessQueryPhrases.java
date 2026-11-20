package com.cretas.aims.config;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Whole-question patterns whose qualifiers are handled by the matching read tool. */
public final class BusinessQueryPhrases {

    private static final String END = "[？?。！!]*";
    private static final Pattern ORDER_PROGRESS = Pattern.compile(
            "(?:请|帮我)?(?:查(?:一下)?|看(?:一下)?)?\\h*(?:销售)?(?:订单|单号)?\\h*"
                    + "((?:[A-Z0-9]+-)*SO-[A-Z0-9]+(?:-[A-Z0-9]+)*)\\h*[,，]?\\h*"
                    + "(?:这[一笔张个]?单|这个订单|订单)?\\h*(?:到哪(?:儿|里)?了|到哪一步了|进度(?:怎么样)?|状态(?:怎么样)?)" + END,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCTION_DEMAND = Pattern.compile(
            "(?:(?:现在|今天|当前)\\h*)?(?:应该|该|要)\\h*排(?:产)?什么\\h*[,，、]\\h*排(?:产)?多少"
                    + "(?:[，,。？?\\h]*(?:这几(?:个|款)产品)?\\h*排产\\h*(?:各)?(?:需要|要)多少人\\h*[,，、]\\h*(?:排)?几(?:个)?班)?" + END);
    private static final Pattern COST_COMPOSITION = Pattern.compile(
            "([\\p{L}\\p{N}·・._（）()\\h-]{2,80}?)\\h*(?:的)?\\h*成本(?:是)?(?:怎么构成(?:的)?|构成是什么|怎么算(?:的)?)" + END);
    private static final Pattern NON_PRODUCT = Pattern.compile(
            "顺便|另外|然后|并且|同时|对比|比较|以及|操作|教程|功能|系统|修改|设置|调整|录入|更新|删除"
                    + "|全厂|全部|所有|每个|各个|最近|过去|上周|本周|上月|本月|这个月|去年|今年|今天|昨天");
    private static final Pattern EXPIRY_LIST = Pattern.compile(
            "(?:需要[，,]\\h*)?(?:(?:请|帮我)?查(?:看|一下)?\\h*)?(?:未来\\h*)?"
                    + "[0-9一二两三四五六七八九十]{1,4}\\h*天\\h*内\\h*(?:到期|过期)的(?:原料|物料|原材料)?批次(?:有哪些)?" + END);
    private static final String WEEK_EXPIRY = "(?:这周|本周)\\h*(?:还?有)?哪些(?:原料|物料|原材料)\\h*(?:快|即将|要)(?:过期|到期)(?:了)?";
    private static final Pattern EXPIRY_WEEK = Pattern.compile(WEEK_EXPIRY + END);
    private static final Pattern EXPIRY_DISPOSAL = Pattern.compile(
            WEEK_EXPIRY + "[？?，,。\\h]+(?:该|应该|要)?怎么处理" + END);

    private BusinessQueryPhrases() {
    }

    public static Optional<String> intentCode(String input) {
        String text = input.strip();
        if (salesOrderNumber(text).isPresent()) return Optional.of("ORDER_STATUS");
        if (PRODUCTION_DEMAND.matcher(text).matches()) return Optional.of("PRODUCTION_DEMAND_ANALYSIS");
        if (costProductName(text).isPresent()) return Optional.of("COST_QUERY");
        if (EXPIRY_DISPOSAL.matcher(text).matches()) return Optional.of("MATERIAL_DISPOSAL_RECOMMENDATION");
        if (EXPIRY_LIST.matcher(text).matches() || EXPIRY_WEEK.matcher(text).matches()) {
            return Optional.of("MATERIAL_EXPIRING_ALERT");
        }
        return Optional.empty();
    }

    public static Optional<String> salesOrderNumber(String input) {
        return argument(ORDER_PROGRESS, input);
    }

    public static Optional<String> costProductName(String input) {
        return argument(COST_COMPOSITION, input)
                .filter(name -> !name.isBlank() && !NON_PRODUCT.matcher(name).find());
    }

    private static Optional<String> argument(Pattern pattern, String input) {
        if (input == null) return Optional.empty();
        Matcher matcher = pattern.matcher(input.strip());
        return matcher.matches() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }
}
