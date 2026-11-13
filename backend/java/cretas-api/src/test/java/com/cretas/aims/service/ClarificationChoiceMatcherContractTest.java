package com.cretas.aims.service;

import com.cretas.aims.entity.conversation.ConversationSession.CandidateIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("澄清选项回复匹配: 序号 / 选项名认, 新话不认")
class ClarificationChoiceMatcherContractTest {

    private static final List<CandidateIntent> OPTIONS = List.of(
            CandidateIntent.builder().intentCode("WORK_PROCESS_TASK_COMPLETE").intentName("完成工序").build(),
            CandidateIntent.builder().intentCode("INVENTORY_REPORT").intentName("库存报表").build());

    @ParameterizedTest(name = "「{0}」→ 第 {1} 项")
    @CsvSource({"1,0", "2,1", "选1,0", "选择2,1", "第一个,0", "第2项,1", "二,1", "1.,0", "1 ,0",
            "完成工序,0", "我选完成工序,0", "就是库存报表吧,1", "inventory_report,1"})
    void picks(String reply, int index) {
        assertThat(ClarificationChoiceMatcher.match(reply, OPTIONS)).isEqualTo(index);
    }

    @ParameterizedTest(name = "「{0}」不是在选")
    @ValueSource(strings = {"3", "0", "完成工序任务1830怎么还没出结果", "我要报工，产量100", "你好", "", "库存报表和完成工序"})
    void doesNotPick(String reply) {
        assertThat(ClarificationChoiceMatcher.match(reply, OPTIONS)).isEqualTo(-1);
    }
}
