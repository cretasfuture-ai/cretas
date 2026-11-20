package com.cretas.aims.controller.workflow;

import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OA 动作结果里带回的插单排程结果 (排进哪天、挤了谁) 要写进幂等账本的 JSON 列。
 * 那里是不带 java.time 模块的序列化器: 原样塞 LocalDate 会让「审批通过」在写账本时失败并整体回滚。
 */
class WorkflowInstanceScheduleOutcomeJsonContractTest {

    private final ObjectMapper plain = new ObjectMapper();

    private Map<String, Object> realOutcome() {
        return new UrgentInsertScheduleService.Proposal(UrgentInsertScheduleService.OK, null,
                LocalDate.of(2027, 1, 5), LocalDate.of(2027, 1, 6),
                List.of(new UrgentInsertScheduleService.Moved("P-9", "PP-9", LocalDate.of(2027, 1, 8),
                        LocalDate.of(2027, 1, 10), 2, LocalDate.of(2027, 1, 9), true, List.of(), List.of("被插单顺延"))),
                List.of("人员未核对"), "FP").toMap();
    }

    @Test
    void theRawOutcomeReallyCannotBeSerialisedByAPlainMapper() {
        // 阳性对照: 不先证明「原样塞会炸」, 下面的通过就可能只是因为数据里本来就没有日期
        assertThatThrownBy(() -> plain.writeValueAsString(realOutcome()))
                .hasMessageContaining("java.time");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonSafeTurnsDatesIntoIsoStringsAtEveryDepthAndKeepsTheRest() throws Exception {
        Object safe = WorkflowInstanceController.jsonSafe(realOutcome());

        String json = plain.writeValueAsString(safe);
        Map<String, Object> back = plain.readValue(json, Map.class);
        assertThat(back.get("insertFirstDay")).isEqualTo("2027-01-05");
        List<Map<String, Object>> moved = (List<Map<String, Object>>) back.get("movedPlans");
        assertThat(moved).hasSize(1);
        assertThat(moved.get(0)).containsEntry("planNumber", "PP-9")
                .containsEntry("newEnd", "2027-01-10")
                .containsEntry("exceedsDue", true);
        assertThat(back.get("exceedsDueCount")).isEqualTo(1);
    }
}
