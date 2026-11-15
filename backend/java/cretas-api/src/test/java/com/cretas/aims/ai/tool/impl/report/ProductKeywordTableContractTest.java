package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1767 第 3 轮口径表 B (1767-round3-spec.md): 产品名抽取。
 * 原则: 只在「目录里的某个产品名完整出现在问句里」时才锁定该产品; 绝不做「问句里的词被目录名包含」的反向包含匹配。
 * 目录 = {卤牛肉, 牛肉, 酱鸭, 低成本套餐, 外婆的红烧肉, 总汇三明治}。
 * 期望写法: 全厂 = 不限产品; 「A+B」= 锁定的产品 (多个按目录顺序); 「未找到:X」= 如实回「没有找到名称含「X」的产品」;
 * 「 | 说明」= 回复开头必须写的统计口径 (organizer 终审: 单个产品「按产品「X」统计：」, 多个「按产品「A」「B」统计：」, 全厂「按全厂统计：」)。
 */
class ProductKeywordTableContractTest {

    private static final String F = "F-TABLE";
    private static final String[] CATALOG = {"卤牛肉", "牛肉", "酱鸭", "低成本套餐", "外婆的红烧肉", "总汇三明治"};

    private static ProductTypeRepository catalogRepo() {
        ProductTypeRepository repo = mock(ProductTypeRepository.class);
        List<ProductType> list = new ArrayList<>();
        for (String n : CATALOG) {
            ProductType t = new ProductType();
            t.setId("PT-" + n);
            t.setName(n);
            list.add(t);
        }
        when(repo.findByFactoryIdAndIsActiveTrue(F)).thenReturn(list);
        return repo;
    }

    @ParameterizedTest(name = "{0} => {1}")
    @CsvSource({
            "成本, 全厂 | 按全厂统计：",
            "汇总成本, 全厂 | 按全厂统计：",
            "全年成本, 全厂 | 按全厂统计：",
            "本周成本, 全厂 | 按全厂统计：",
            "近三个月毛利, 全厂 | 按全厂统计：",
            "合计成本, 全厂 | 按全厂统计：",
            "卤牛肉成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "牛肉成本, 牛肉 | 按产品「牛肉」统计：",
            "本周卤牛肉成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            // 最长完整命中照用; 开头写明统计的是卤牛肉, 不判断问句里多出来的字
            "卤牛肉片成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "卤牛肉和酱鸭成本, 卤牛肉+酱鸭 | 按产品「卤牛肉」「酱鸭」统计：",
            "外婆的红烧肉成本, 外婆的红烧肉 | 按产品「外婆的红烧肉」统计：",
            "低成本套餐毛利, 低成本套餐 | 按产品「低成本套餐」统计：",
            "总汇三明治成本, 总汇三明治 | 按产品「总汇三明治」统计：",
            "牛油果成本, 未找到:牛油果",
            // organizer 终审补的 7 行: 上一版换名说明在这些问法上误报「没有名为「卤牛肉总」…」
            "卤牛肉总成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "卤牛肉平均成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "卤牛肉3月成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "卤牛肉第三季度成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "卤牛肉近半年成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "卤牛肉每吨成本, 卤牛肉 | 按产品「卤牛肉」统计：",
            "卤牛肉为什么成本高, 卤牛肉 | 按产品「卤牛肉」统计："})
    void table(String question, String expected) {
        ReportToolSchemas.ProductResolution r = ReportToolSchemas.resolveProduct(catalogRepo(), F, question);
        // 期望 = 解析结果 [+ " | " + 回复开头的统计口径]; 没找到的行没有口径说明 (回复本身就是说明)
        String leadIn = r.leadIn(false);
        assertEquals(expected, r.describe() + (leadIn == null ? "" : " | " + leadIn), question);
    }

    /** 规则 3: 目录每次问答只查一次。 */
    @Test
    void catalogIsLoadedOncePerResolution() {
        ProductTypeRepository repo = catalogRepo();
        ReportToolSchemas.resolveProduct(repo, F, "牛油果成本");
        verify(repo, times(1)).findByFactoryIdAndIsActiveTrue(F);
    }

    /** 「没有找到」的文案按表写「名称含「X」」; 不把目录里别的产品当答案。 */
    @Test
    void notFoundMessageNamesTheWordAsked() {
        String msg = ReportToolSchemas.resolveProduct(catalogRepo(), F, "牛油果成本").refusalMessage("统计成本", true);
        assertEquals(true, msg != null && msg.contains("没有找到名称含「牛油果」的"), String.valueOf(msg));
    }

    /** 多个产品都出现: 单产品口径的工具明确说「问到了 N 个产品」, 不悄悄丢掉其中一个。 */
    @Test
    void twoProductsAreSaidPlainly_notDropped() {
        String msg = ReportToolSchemas.resolveProduct(catalogRepo(), F, "卤牛肉和酱鸭成本").refusalMessage("统计成本", true);
        assertEquals(true, msg != null && msg.contains("问到了 2 个产品") && msg.contains("卤牛肉") && msg.contains("酱鸭"), String.valueOf(msg));
    }
}
