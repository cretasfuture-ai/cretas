package com.cretas.aims.ai.tool;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 共用状态筛选 {@link StatusFilter} 的规则 (ERP-S6-10 同因)。批次的完整词表用例见 MaterialBatchStatusPhraseContractTest。 */
class StatusFilterContractTest {

    private static final Map<String, Object> SHIPMENT = StatusFilter.property("出货单状态", StatusFilter.table(
            StatusFilter.words("PENDING", "待发货", "未发货"),
            StatusFilter.words("SHIPPED", "已发货"),
            StatusFilter.words("CANCELLED", "已取消")));
    private static final Map<String, Object> CHECK = StatusFilter.property("检验结果", StatusFilter.table(
            StatusFilter.words("PASS", "合格", "通过"),
            StatusFilter.words("FAIL", "不合格", "未通过")));
    private static final Map<String, Object> SCALE = StatusFilter.property("设备状态", StatusFilter.table(
            StatusFilter.words("idle", "空闲"), StatusFilter.words("offline", "离线")));

    @Test
    void longerWordsWinSoNegatedFormsAreNotReadAsTheirPositive() {
        assertThat(StatusFilter.fromSentence(CHECK, "不合格的检验记录")).contains("FAIL");
        assertThat(StatusFilter.fromSentence(CHECK, "合格的检验记录")).contains("PASS");
        assertThat(StatusFilter.fromSentence(CHECK, "未通过的检验")).contains("FAIL");
    }

    @Test
    void codesAreRecognisedInAnyCaseAndKeepTheToolsOwnCasing() {
        assertThat(StatusFilter.fromSentence(SHIPMENT, "status=shipped 的出货单")).contains("SHIPPED");
        assertThat(StatusFilter.fromSentence(SCALE, "OFFLINE 的秤")).contains("offline");
        assertThat(StatusFilter.normalize(SCALE, "IDLE")).isEqualTo("idle");
        assertThat(StatusFilter.normalize(SHIPMENT, "已发货")).isEqualTo("SHIPPED");
        assertThat(StatusFilter.normalize(SHIPMENT, "LOST")).isEqualTo("LOST");
    }

    @Test
    void noStatusMentionedIsNoFilter() {
        assertThat(StatusFilter.fromSentence(SHIPMENT, "查询出货单")).isEmpty();
        assertThat(StatusFilter.fromSentence(SHIPMENT, "出货单状态分布")).isEmpty();
        assertThat(StatusFilter.fromSentence(SHIPMENT, "订单的状态")).isEmpty();
    }

    @Test
    void aNamedButUnknownStatusIsRejectedWithTheChoices() {
        assertThatThrownBy(() -> StatusFilter.fromSentence(SHIPMENT, "查询冻结状态的出货单"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没认出你说的状态「冻结」").hasMessageContaining("已发货(SHIPPED)");
        assertThatThrownBy(() -> StatusFilter.fromSentence(SHIPMENT, "状态为丢件的出货单"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("丢件");
    }

    @Test
    void twoStatusesInOneSentenceAreRejected() {
        assertThatThrownBy(() -> StatusFilter.fromSentence(SHIPMENT, "已发货和已取消的出货单"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("一次只能按一个状态查询");
    }

    @Test
    void applyPrefersTheExplicitParamAndOnlyActsOnToolsThatDeclaredWords() {
        Map<String, Object> schema = Map.of("properties", Map.of("status", SHIPMENT));

        Map<String, Object> explicit = new HashMap<>(Map.of("status", "已取消", "userInput", "已发货的出货单"));
        StatusFilter.apply(schema, explicit);
        assertThat(explicit).containsEntry("status", "CANCELLED");

        Map<String, Object> said = new HashMap<>(Map.of("userInput", "已发货的出货单"));
        StatusFilter.apply(schema, said);
        assertThat(said).containsEntry("status", "SHIPPED");

        Map<String, Object> undeclared = new HashMap<>(Map.of("userInput", "已发货的出货单"));
        StatusFilter.apply(Map.of("properties", Map.of("status", Map.of("type", "string"))), undeclared);
        assertThat(undeclared).doesNotContainKey("status");
    }

    @Test
    void withoutEnumKeepsTheWordsForToolsThatAcceptLists() {
        Map<String, Object> p = StatusFilter.withoutEnum(StatusFilter.property("提醒", StatusFilter.table(
                StatusFilter.words("PENDING", "待处理"))));
        assertThat(p).doesNotContainKey("enum").containsKey(StatusFilter.WORDS);
        assertThat(StatusFilter.normalize(p, "PENDING,SNOOZED")).isEqualTo("PENDING,SNOOZED");
        assertThat(StatusFilter.fromSentence(p, "待处理的提醒")).contains("PENDING");
        assertThat(List.of()).isEmpty();
    }
}
