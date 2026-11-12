package com.cretas.aims.service.execution;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTENT-DISTILL-20261111 第二批: 给识别层的任务状态摘要只含白名单字段, 续轮规则两个入口共用。
 */
class TaskStateSummaryWhitelistContractTest {

    @Test
    void whitelistIsTheLiteralSet() {
        // 往白名单里加字段必须改这两行 (以及 Python test_ai_task_state.py) —— 加字段测试要红
        assertThat(TaskStateSummary.TOP_KEYS).isEqualTo(Set.of("tasks"));
        assertThat(TaskStateSummary.TASK_KEYS).isEqualTo(Set.of("level", "intentCode", "params"));
    }

    @Test
    void summaryCarriesOnlyWhitelistedKeysAndNeverFreeText() {
        ConversationSession suspended = session("PROCESSING_BATCH_COMPLETE", Map.of(
                "batchId", "B-20261112-01", "operatorName", "张三", "notes", "今天温度偏高", "quantity", "52.5"));
        suspended.addAssistantMessage("好的，已为您查询张三的库存");

        Map<String, Object> summary = TaskStateSummary.build(
                new TaskStateSummary.Current("MATERIAL_INVENTORY_QUERY", Map.of("page", 2, "size", 20)),
                List.of(suspended), false);

        assertThat(summary.keySet()).isEqualTo(TaskStateSummary.TOP_KEYS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) summary.get("tasks");
        assertThat(tasks).hasSize(2);
        tasks.forEach(t -> assertThat(t.keySet()).isEqualTo(TaskStateSummary.TASK_KEYS));
        assertThat(tasks.get(0)).containsEntry("level", 0).containsEntry("intentCode", "MATERIAL_INVENTORY_QUERY")
                .containsEntry("params", Map.of("page", "2", "size", "20"));
        assertThat(tasks.get(1)).containsEntry("level", 1)
                .containsEntry("params", Map.of("batchId", "B-20261112-01", "operatorName", "已填",
                        "notes", "已填", "quantity", "52.5"));
        String flat = summary.toString();
        for (String leaked : List.of("张三", "温度", "已为您", "F006", "1686", suspended.getSessionId(), "USER")) {
            assertThat(flat).as(leaked).doesNotContain(leaked);
        }
    }

    @Test
    void processSecretWithholdsNumbers() {
        ConversationSession suspended = session("PROCESSING_BATCH_COMPLETE",
                Map.of("batchId", "B-1", "ratio", "0.35", "quantity", "52"));
        Map<String, Object> summary = TaskStateSummary.build(null, List.of(suspended), true);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) ((List<Map<String, Object>>) summary.get("tasks"))
                .get(0).get("params");
        assertThat(params).containsEntry("ratio", "已填").containsEntry("quantity", "已填")
                .containsEntry("batchId", "B-1");
    }

    @Test
    void egressRebuildsFromTheWhitelistOnly() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("level", 1);
        task.put("intentCode", "A_B");
        task.put("params", Map.of("batchId", "B-1", "x", "已填"));
        task.put("assistantText", "助手正文"); // 白名单外: 必须被丢掉
        Map<String, Object> dirty = new LinkedHashMap<>();
        dirty.put("tasks", List.of(task));
        dirty.put("factoryId", "F006");

        assertThat(TaskStateEgress.stringValues(dirty)).containsExactly("B-1");
        Map<String, Object> out = TaskStateEgress.withRedactedValues(dirty, i -> "[REDACTED-" + i + "]", 3);

        assertThat(out.keySet()).isEqualTo(TaskStateSummary.TOP_KEYS);
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = ((List<Map<String, Object>>) out.get("tasks")).get(0);
        assertThat(sent.keySet()).isEqualTo(TaskStateSummary.TASK_KEYS);
        assertThat(sent.get("params")).isEqualTo(Map.of("batchId", "[REDACTED-3]", "x", "已填"));
        assertThat(TaskStateEgress.withRedactedValues(Map.of(), i -> "", 0)).isNull();
    }

    // ---------------- 续轮规则 (两个入口共用) ----------------

    @Test
    void anUnrelatedSentenceInterruptsAndANumberAnswers() {
        List<RequiredParameter> pending = List.of(RequiredParameter.builder().name("taskId").type("INTEGER").build());
        assertThat(TaskContinuationRule.forActive("先看下库存，回头再报工", pending, s -> false))
                .isEqualTo(TaskContinuationRule.Action.INTERRUPT);
        assertThat(TaskContinuationRule.forActive("1827号", pending, s -> false))
                .isEqualTo(TaskContinuationRule.Action.ANSWER);
    }

    @Test
    void resumeTargetsFollowThePhraseMapOrTheStackTop() {
        ConversationSession report = session("PROCESSING_BATCH_COMPLETE", Map.of());
        ConversationSession receive = session("MATERIAL_RECEIVE", Map.of());
        List<ConversationSession> stack = List.of(receive, report); // 栈顶在前
        java.util.function.Function<String, Optional<String>> phrases = s -> switch (s) {
            case "报工" -> Optional.of("PROCESSING_BATCH_COMPLETE");
            case "查库存" -> Optional.of("MATERIAL_INVENTORY_QUERY");
            default -> Optional.empty();
        };
        assertThat(TaskContinuationRule.resumeTarget("报工", stack, phrases)).contains(report);
        assertThat(TaskContinuationRule.resumeTarget("好了，继续报工", stack, phrases)).contains(report);
        assertThat(TaskContinuationRule.resumeTarget("继续", stack, phrases)).contains(receive);
        assertThat(TaskContinuationRule.resumeTarget("接着刚才的吧", stack, phrases)).contains(receive);
        // 说的是栈里没有的事 / 不带恢复说法的别的话 → 不恢复
        assertThat(TaskContinuationRule.resumeTarget("继续查库存", stack, phrases)).isEmpty();
        assertThat(TaskContinuationRule.resumeTarget("继续看看今天的订单", stack, phrases)).isEmpty();
        assertThat(TaskContinuationRule.resumeTarget("今天的订单", stack, phrases)).isEmpty();
        assertThat(TaskContinuationRule.resumeTarget("继续", List.of(), phrases)).isEmpty();
    }

    private static ConversationSession session(String intent, Map<String, String> collected) {
        ConversationSession s = ConversationSession.createForParameterCollection("F006", 1686L, intent,
                new ArrayList<>(List.of(RequiredParameter.builder().name("taskId").label("任务号").type("INTEGER").build())),
                "请提供任务号");
        s.setSessionId(java.util.UUID.randomUUID().toString());
        s.setCollectedParameters(new LinkedHashMap<>(collected));
        s.setLastActiveAt(LocalDateTime.now());
        return s;
    }
}
