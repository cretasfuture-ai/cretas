package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Produces concise business guidance for the current required factory decision. */
final class FactoryDecisionGuidanceCatalog {

    private record Guidance(String why, List<String> impacts, List<String> examples) {}

    private static final Map<String, Guidance> ROOT = Map.ofEntries(
            entry("factoryName", "这会成为工厂方案、配置草稿和后续复核里的业务名称。",
                    List.of("工厂档案", "蓝图版本", "后续复核"), List.of("华东中央厨房", "冷藏卤味一厂")),
            entry("businessType", "产品范围决定后面要追问哪些工序、物料和存放方式。",
                    List.of("产品目录", "工序范围", "物料范围"),
                    List.of("冷藏卤味与气调熟食", "面包和常温糕点",
                            "果汁饮料与调味酱", "速冻预制菜与中央厨房")),
            entry("industryHint", "行业归类只用于选择平台推荐的问题和配置结构，不会替你填写答案。",
                    List.of("平台建议", "追问顺序"), List.of("卤味熟食", "饮料")),
            entry("stepCount", "道数决定要展开多少道真实工序；少一道会让投入、产出和路线断开。",
                    List.of("工序清单", "产品路线", "产出衔接"), List.of("6 道", "12 道")),
            entry("productSkus", "成品规格和包装换算会影响库存单位、装箱和销售交付。",
                    List.of("成品 SKU", "包装换算", "库存单位"), List.of("80g/盒，一箱 20 盒", "500ml/瓶，一箱 12 瓶")),
            entry("customerSupplied", "客供料与自购物料的所有权、成本和领料边界不同。",
                    List.of("物料所有权", "成本归集", "领料边界"), List.of("没有客供料", "部分原料由客户提供")),
            entry("productCount", "不同成品可能共用前处理、也可能在中途分线，必须逐个绑定路线。",
                    List.of("成品 SKU", "产品工序链", "共享前处理"), List.of("2 个成品", "6 个成品")),
            entry("warehouseSetup", "存放区决定原料、半成品和成品在各阶段能否正确入库、移库和追溯。",
                    List.of("仓储区域", "库存阶段", "批次追溯"), List.of("原料冷库（原料）", "车间暂存（暂存）")),
            entry("materialCoding", "这里只确定物料的大类边界，具体物料编码仍由后续物料主数据确认。",
                    List.of("物料分类", "编码前缀"), List.of("原料/辅料/包材", "原料和包材")),
            entry("metricWatches", "只有明确指标和阈值，系统才能知道什么时候提醒管理人员。",
                    List.of("生产指标", "异常提醒"), List.of("良品率低于 95%", "单位成本超过 30 元")),
            entry("indicatorOptOut", "这里是在默认管理看板上做减法，不会删除历史数据。",
                    List.of("管理看板", "指标可见性"), List.of("都要看", "停用本月销售总额")),
            entry("moduleOptOut", "关闭模块会同时影响页面和后端接口，必须按真实业务选择。",
                    List.of("功能模块", "页面可见性", "接口权限"), List.of("都要", "不用餐饮和物流")),
            entry("alerts", "提醒项决定哪些经营事件会主动通知，而不是只留在业务列表里。",
                    List.of("提醒规则", "通知事件"), List.of("库存不足和质检异常", "都不用")),
            entry("createAccounts", "是否创建初始账号只影响人员登录准备，不会替你分配真实岗位人员。",
                    List.of("初始账号", "登录准备"), List.of("建一套初始账号", "先不建")),
            entry("haccpPoints", "关键控制点会进入方案复核；没有明确限值的点仍会保持未完成。",
                    List.of("工序控制点", "质量复核"), List.of("杀菌、金检", "暂不设")),
            entry("haccpLimits", "明确的限值才能形成可执行控制条件，系统不会替工厂编一个数字。",
                    List.of("控制限值", "异常判定"), List.of("中心温度≥75℃", "Fe≥2.0mm 剔除"))
    );

    private FactoryDecisionGuidanceCatalog() {}

    static FactoryDecisionPacket build(
            FactoryAssemblyConversationSession.RequiredParameter parameter,
            String suggestion,
            String state) {
        String field = parameter.getName();
        Guidance guidance = guidance(field, parameter.getLabel());
        return FactoryDecisionPacket.builder()
                .decisionId("factory-decision:" + field)
                .field(field)
                .label(parameter.getLabel())
                .question(parameter.getValidationHint() == null || parameter.getValidationHint().isBlank()
                        ? parameter.getLabel() : parameter.getValidationHint())
                .whyItMatters(guidance.why())
                .inputType(parameter.getInputType() == null ? "NARRATIVE" : parameter.getInputType())
                .options(parameter.getOptions() == null ? List.of() : List.copyOf(parameter.getOptions()))
                .multiSelect(parameter.isMultiSelect())
                .allowNone(parameter.isAllowNone())
                .state(state)
                .acceptedDecision(parameter.getValue())
                .unresolvedFollowUp(parameter.isUserDeferred()
                        ? "你已标记为暂时不确定。可以继续回答其他问题，但完整方案确认前必须回到这里补充。"
                        : null)
                .recommendation(suggestion)
                .recommendationReason(suggestion == null ? null
                        : "平台按当前行业准备的可编辑建议；你明确采纳后才会成为答案。")
                .guidanceChoices(choices(parameter, guidance.examples()))
                .downstreamImpacts(guidance.impacts())
                .build();
    }

    private static Guidance guidance(String field, String label) {
        Guidance direct = ROOT.get(field);
        if (direct != null) return direct;
        String suffix = field == null ? "" : field.substring(field.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (suffix) {
            case "name" -> new Guidance("名称会成为这道工序或成品在路线、报工和复核中的业务标识。",
                    List.of("工序/成品标识", "路线节点"), List.of("解冻", "调配", "80g 牛腱肉"));
            case "inputmaterial" -> new Guidance("每道工序的投入必须接得上上一道产出或明确的新领原料。",
                    List.of("投入衔接", "领料需求", "路线完整性"), List.of("解冻后的牛腱肉", "新领糖浆"));
            case "auxmaterials" -> new Guidance("辅料会进入配方、领料和成本；没有时也要明确说没有。",
                    List.of("辅料配方", "领料需求", "成本"), List.of("盐、香辛料", "没有"));
            case "packmaterials" -> new Guidance("包材决定包装工序的领料和成品包装结构。",
                    List.of("包材配方", "包装工序", "成本"), List.of("气调盒、顶膜、标签", "没有"));
            case "output" -> new Guidance("产出名称和单位用于连接下一道工序，并区分半成品与成品。",
                    List.of("产出衔接", "库存单位", "路线节点"), List.of("熟制牛腱肉，kg", "成品酱料，瓶"));
            case "params" -> new Guidance("工艺参数让流程从名称清单变成可执行的生产要求。",
                    List.of("工序参数", "执行提示"), List.of("中心温度≥75℃", "搅拌 20 分钟"));
            case "yieldrate" -> new Guidance("出成率会影响投料、产量估算和成本，不能用包装数量代替。",
                    List.of("产量估算", "投料需求", "成本"), List.of("0.92", "加汁后 1.18", "包装工序不计"));
            case "byproducts" -> new Guidance("副产物和废料会影响回收、报废和成本边界。",
                    List.of("副产物", "废料处置", "成本"), List.of("边角料可回收", "没有"));
            case "steps" -> new Guidance("成品路线必须按顺序绑定真实工序，才能区分共线和分线。",
                    List.of("产品工序链", "共线/分线"), List.of("解冻→滚揉→熟制→包装", "调配→灌装→贴标"));
            default -> {
                String safeLabel = label == null || label.isBlank() ? "这项信息" : label;
                yield new Guidance("这项答案会进入工厂方案；不确定时可以明确留作待补充，系统不会替你猜。",
                        List.of(safeLabel),
                        List.of(safeLabel + "目前确定的是：", safeLabel + "最常见的情况是："));
            }
        };
    }

    private static List<FactoryDecisionPacket.GuidanceChoice> choices(
            FactoryAssemblyConversationSession.RequiredParameter parameter,
            List<String> examples) {
        List<FactoryDecisionPacket.GuidanceChoice> result = new ArrayList<>();
        List<String> options = parameter.getOptions();
        if (options != null && !options.isEmpty() && options.size() <= 4) {
            for (String option : options) {
                result.add(choice(option, option, null, "选择后会作为明确答案进入方案。"));
            }
            if (result.size() == 1) {
                String safeLabel = parameter.getLabel() == null || parameter.getLabel().isBlank()
                        ? "这项信息" : parameter.getLabel();
                result.add(choice("都不符合，自己说明", null,
                        safeLabel + "实际情况是：", "这是可编辑开头，不会自动提交。"));
            }
            return result;
        }
        int exampleLimit = options != null && options.size() > 4 ? 3 : 4;
        for (String example : examples.stream().limit(exampleLimit).toList()) {
            result.add(choice(example, null, example, "这是可编辑例子，不会自动提交。"));
        }
        if (options != null && options.size() > 4) {
            result.add(choice("按实际情况选择", null,
                    "展开完整选项后可多选或只选一项", "完整选项仍由服务端题目契约提供。"));
        }
        return result;
    }

    private static FactoryDecisionPacket.GuidanceChoice choice(
            String label, String value, String example, String consequence) {
        return FactoryDecisionPacket.GuidanceChoice.builder()
                .label(label).value(value).example(example).consequence(consequence).build();
    }

    private static Map.Entry<String, Guidance> entry(
            String field, String why, List<String> impacts, List<String> examples) {
        return Map.entry(field, new Guidance(why, impacts, examples));
    }
}
