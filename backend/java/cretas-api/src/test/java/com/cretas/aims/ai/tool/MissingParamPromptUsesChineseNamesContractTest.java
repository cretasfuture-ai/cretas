package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.impl.bom.BomRecipeQueryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺参追问的通用生成处 ({@link AbstractBusinessTool#generateClarificationQuestions}) 以前对没登记的字段名原样输出
 * 「请提供productTypeId」, 把开发字段名发给用户。钉住: 常用实体编号字段按中文业务名问, 没登记的英文字段取 schema
 * 里的中文说明或说「相关信息」, 任何一种都不带字段名。
 */
@DisplayName("缺参追问: 用中文业务名, 不露字段名")
class MissingParamPromptUsesChineseNamesContractTest {

    @SuppressWarnings("unchecked")
    private static List<String> questions(AbstractBusinessTool tool, String... params) {
        return (List<String>) ReflectionTestUtils.invokeMethod(
                tool, "generateClarificationQuestions", (Object) List.of(params));
    }

    @Test
    @DisplayName("常用实体编号字段按中文业务名问")
    void commonIdFieldsAreAskedByBusinessName() {
        List<String> qs = questions(new BomRecipeQueryTool(), "productTypeId", "recipeId", "customerId", "equipmentId");
        assertThat(qs).containsExactly("请提供产品", "请提供配方", "请提供客户", "请提供设备");
    }

    @Test
    @DisplayName("没登记的英文字段: 取 schema 的中文说明, 没有就说「相关信息」, 不原样输出字段名")
    void unknownEnglishFieldNeverLeaks() {
        BomRecipeQueryTool tool = new BomRecipeQueryTool();
        List<String> qs = questions(tool, "someInternalFlag");
        assertThat(qs).containsExactly("请提供相关信息");
        assertThat(String.join("", qs)).doesNotContain("someInternalFlag");
    }

    @Test
    @DisplayName("阴性对照: 已有的中文业务名与已有问句不变")
    void existingNamesAreUnchanged() {
        List<String> qs = questions(new BomRecipeQueryTool(), "batchNumber", "quantity", "warningDays");
        assertThat(qs).containsExactly("请问您要操作哪个批次？请提供批次号。", "请问数量是多少？", "请提供预警天数");
    }
}
