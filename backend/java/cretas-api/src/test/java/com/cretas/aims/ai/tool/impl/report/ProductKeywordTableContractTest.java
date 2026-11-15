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
 * 「 | 说明」= 回复开头必须写的说明 (问句里的名字比命中的目录名多了字)。
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
            "成本, 全厂",
            "汇总成本, 全厂",
            "全年成本, 全厂",
            "本周成本, 全厂",
            "近三个月毛利, 全厂",
            "合计成本, 全厂",
            "卤牛肉成本, 卤牛肉",
            "牛肉成本, 牛肉",
            "本周卤牛肉成本, 卤牛肉",
            // organizer 裁定: 最长完整命中照用, 但问句里的名字多了字时回复要明说按哪个产品回答
            "卤牛肉片成本, 卤牛肉 | 没有名为「卤牛肉片」的产品，下面按「卤牛肉」回答。",
            "卤牛肉和酱鸭成本, 卤牛肉+酱鸭",
            "外婆的红烧肉成本, 外婆的红烧肉",
            "低成本套餐毛利, 低成本套餐",
            "总汇三明治成本, 总汇三明治",
            "牛油果成本, 未找到:牛油果"})
    void table(String question, String expected) {
        ReportToolSchemas.ProductResolution r = ReportToolSchemas.resolveProduct(catalogRepo(), F, question);
        // 期望 = 解析结果 [+ " | " + 回复开头必须写的说明]; 其它行没有说明 (note 为 null)
        assertEquals(expected, r.describe() + (r.note() == null ? "" : " | " + r.note()), question);
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
