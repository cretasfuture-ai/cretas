package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;

import java.util.List;

/**
 * 按产品名找产品的公共入口: 其它包的工具 (配方查询等) 与成本 / 毛利类报表工具共用同一份解析
 * ({@link ReportToolSchemas#resolveProduct}: 去「演示·」前缀与规格、整词剥问句用语、只认名称完整出现在原话里的在用产品)。
 * 不另写第二套名称匹配。
 *
 * <p>结果四种: 没说产品 ({@link Outcome#NONE}) / 唯一命中 ({@link Outcome#ONE}) /
 * 多个命中 ({@link Outcome#MANY}, 列候选让用户选) / 查不到 ({@link Outcome#NOT_FOUND}, 如实说)。
 */
public final class ProductNameLookup {

    private ProductNameLookup() {
    }

    public enum Outcome { NONE, ONE, MANY, NOT_FOUND }

    /**
     * @param products 命中的产品 (ONE=1 个, MANY=多个, 其余为空)
     * @param message  MANY / NOT_FOUND 时可直接回给用户的中文说明, 其余为 null
     */
    public record Result(Outcome outcome, List<ProductType> products, String message) {
        public ProductType only() {
            return outcome == Outcome.ONE ? products.get(0) : null;
        }
    }

    /**
     * @param raw 产品名, 也可以直接是用户原话 (解析会剥掉问句 / 指标 / 时间用语)
     * @param what 「无法{what}」里的动作, 如「查看配方」
     */
    public static Result resolve(ProductTypeRepository repo, String factoryId, String raw, String what) {
        ReportToolSchemas.ProductResolution r = ReportToolSchemas.resolveProduct(repo, factoryId, raw);
        switch (r.scope()) {
            case MATCHED -> {
                if (r.products().size() == 1) {
                    return new Result(Outcome.ONE, r.products(), null);
                }
                return new Result(Outcome.MANY, r.products(),
                        "您问到了 " + r.products().size() + " 个产品：" + String.join("、", r.productNames())
                                + "。请说清楚要看哪一个（说完整产品名）。");
            }
            case NOT_FOUND -> {
                return new Result(Outcome.NOT_FOUND, List.of(), r.refusalMessage(what, false));
            }
            default -> {
                return new Result(Outcome.NONE, List.of(), null);
            }
        }
    }
}
