package com.cretas.aims.service.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追问解读规则表。每条「命中」都配一条「同一句话换个上一步 / 没有上一步就不命中」的对照 ——
 * 否则分不清是规则在看上下文, 还是这句话本身就被无条件映射了。
 */
class FollowUpResolverTest {

    private static final long NOW = 1_800_000_000_000L;
    private final FollowUpResolver resolver = new FollowUpResolver();

    private static Map<String, Object> last(String intentCode, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentCode", intentCode);
        m.put("params", params);
        m.put("at", NOW - 60_000L);
        return m;
    }

    private static Map<String, Object> lastWithTool(String intentCode, String tool, Map<String, Object> params) {
        Map<String, Object> m = last(intentCode, params);
        m.put("toolName", tool);
        return m;
    }

    private Optional<FollowUpResolver.FollowUp> resolve(String input, Object lastAction) {
        return resolver.resolve(input, lastAction, NOW);
    }

    // ---------------- DONE after START ----------------

    @ParameterizedTest
    @ValueSource(strings = {"好了", "好了。", "做完了", "弄完了", "完成了", "搞定", "搞定了", "结束了", "包装完了",
            "分割完成了", " 好了 ", "好啦！"})
    void doneAfterStartCompletesTheSameTask(String input) {
        Optional<FollowUpResolver.FollowUp> f = resolve(input, last("WORK_PROCESS_TASK_START", Map.of("taskId", 12)));

        assertThat(f).as(input).isPresent();
        assertThat(f.get().ruleId()).isEqualTo(FollowUpResolver.RULE_WP_DONE);
        assertThat(f.get().intentCode()).isEqualTo("WORK_PROCESS_TASK_COMPLETE");
        assertThat(f.get().params()).containsExactly(Map.entry("taskId", 12L));
    }

    @ParameterizedTest
    @CsvSource({"好了，一共六十箱,60", "做完了60箱,60", "六十箱做完了,60", "好了 一共一百五十箱,150", "完成了，共12件,12"})
    void doneWithQuantityAlsoSetsActualQuantity(String input, long expected) {
        Optional<FollowUpResolver.FollowUp> f = resolve(input, last("WORK_PROCESS_TASK_START", Map.of("taskId", 12)));

        assertThat(f).as(input).isPresent();
        assertThat(f.get().params()).containsEntry("taskId", 12L).containsEntry("actualQuantity", expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"好了", "做完了", "包装完了"})
    void doneDoesNotFireWithoutAStartedTask(String input) {
        // 对照: 同一句话, 上一步不是「开始工序」/ 没有上一步 / 上一步已经完成
        assertThat(resolve(input, last("MATERIAL_BATCH_USE", Map.of("batchId", "B1", "quantity", 20)))).isEmpty();
        assertThat(resolve(input, last("WORK_PROCESS_TASK_COMPLETE", Map.of("taskId", 12)))).isEmpty();
        assertThat(resolve(input, null)).isEmpty();
        assertThat(resolve(input, Map.of())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"好了吗", "好了？", "还没好了", "料用完了", "卖完了", "好了好了好了",
            "好了，现在去看一下今天的报表吧谢谢"})
    void doneIgnoresQuestionsNegationsAndNonCompletionPhrases(String input) {
        assertThat(resolve(input, last("WORK_PROCESS_TASK_START", Map.of("taskId", 12)))).as(input).isEmpty();
    }

    @Test
    void doneWithoutKnownTaskIdStillRoutesButCarriesNoId() {
        Optional<FollowUpResolver.FollowUp> f = resolve("好了", last("WORK_PROCESS_TASK_START", Map.of()));
        assertThat(f).isPresent();
        assertThat(f.get().params()).isEmpty();
    }

    // ---------------- NEXT after COMPLETE ----------------

    @ParameterizedTest
    @ValueSource(strings = {"下一个", "下一道", "接着来", "继续下一个", "下一个吧", "开始下一个"})
    void nextAfterCompleteStartsANewTaskWithoutGuessingWhich(String input) {
        Optional<FollowUpResolver.FollowUp> f = resolve(input, last("WORK_PROCESS_TASK_COMPLETE", Map.of("taskId", 12)));

        assertThat(f).as(input).isPresent();
        assertThat(f.get().ruleId()).isEqualTo(FollowUpResolver.RULE_WP_NEXT);
        assertThat(f.get().intentCode()).isEqualTo("WORK_PROCESS_TASK_START");
        assertThat(f.get().params()).as("不带 taskId: 已完成的那道不能再开始一次").isEmpty();
    }

    @Test
    void nextDoesNotFireAfterOtherActionsOrForBareContinue() {
        assertThat(resolve("下一个", last("WORK_PROCESS_TASK_START", Map.of("taskId", 12)))).isEmpty();
        assertThat(resolve("下一个", null)).isEmpty();
        // 「继续」单说是 CONTEXT_CONTINUE 的地盘, 这里不碰
        assertThat(resolve("继续", last("WORK_PROCESS_TASK_COMPLETE", Map.of("taskId", 12)))).isEmpty();
    }

    // ---------------- QTY_CHANGE ----------------

    @ParameterizedTest
    @CsvSource({
            "再加十公斤,30", "再加10kg,30", "多五公斤,25", "加二十,40", "少五公斤,15", "减两公斤,18",
            "改成五十公斤,50", "改为一百五,150", "三十公斤就行,30", "再加十五公斤,35", "改成2.5公斤,2.5"})
    void quantityChangeAdjustsTheSameIntent(String input, String expected) {
        Map<String, Object> params = new HashMap<>();
        params.put("batchId", "MB-7");
        params.put("quantity", 20);
        params.put("unit", "kg");
        Optional<FollowUpResolver.FollowUp> f = resolve(input, last("MATERIAL_BATCH_USE", params));

        assertThat(f).as(input).isPresent();
        assertThat(f.get().ruleId()).isEqualTo(FollowUpResolver.RULE_QTY_CHANGE);
        assertThat(f.get().intentCode()).isEqualTo("MATERIAL_BATCH_USE");
        assertThat(f.get().params()).containsEntry("batchId", "MB-7").containsEntry("unit", "kg");
        assertThat(new BigDecimal(f.get().params().get("quantity").toString()))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void quantityChangeDoesNotFireWithoutAQuantityBearingWrite() {
        // 对照: 同一句「再加十公斤」—— 上一步是读 / 没数量 / 没有上一步
        assertThat(resolve("再加十公斤", last("MATERIAL_BATCH_QUERY", Map.of("batchId", "MB-7", "quantity", 20)))).isEmpty();
        assertThat(resolve("再加十公斤", last("MATERIAL_BATCH_USE", Map.of("batchId", "MB-7")))).isEmpty();
        assertThat(resolve("再加十公斤", null)).isEmpty();
    }

    @Test
    void quantityChangeRefusesNonPositiveResultsAndIncompatibleUnits() {
        Map<String, Object> params = Map.of("batchId", "MB-7", "quantity", 20, "unit", "kg");
        assertThat(resolve("少二十公斤", last("MATERIAL_BATCH_USE", params))).as("减到 0").isEmpty();
        assertThat(resolve("减三十", last("MATERIAL_BATCH_USE", params))).as("减成负数").isEmpty();
        assertThat(resolve("再加十斤", last("MATERIAL_BATCH_USE", params))).as("斤 ≠ 公斤").isEmpty();
        assertThat(resolve("再加十箱", last("MATERIAL_BATCH_USE", params))).as("箱 ≠ 公斤").isEmpty();
        assertThat(resolve("再加十千克", last("MATERIAL_BATCH_USE", params))).as("千克 = 公斤").isPresent();
    }

    @ParameterizedTest
    @CsvSource({"十,10", "二十,20", "五十,50", "一百五,150", "两,2", "十五,15", "一百零五,105", "三百二十,320",
            "一千二,1200", "一千零二十,1020", "60,60", "2.5,2.5"})
    void numbersParse(String raw, String expected) {
        assertThat(FollowUpResolver.parseNumber(raw)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"零", "百", "一二", "十十", "百十", "0", ""})
    void malformedNumbersAreRejected(String raw) {
        assertThat(FollowUpResolver.parseNumber(raw)).as(raw).isNull();
    }

    // ---------------- APPROVE / REJECT ----------------

    @ParameterizedTest
    @ValueSource(strings = {"批了吧", "批了", "同意", "通过", "可以批", "张三那个批了"})
    void approveAfterSinglePurchaseOrderListCarriesTheOrderId(String input) {
        Optional<FollowUpResolver.FollowUp> f = resolve(input,
                lastWithTool("PURCHASE_ORDER_LIST", "purchase_order_list", Map.of("orderId", "PO-1")));

        assertThat(f).as(input).isPresent();
        assertThat(f.get().ruleId()).isEqualTo(FollowUpResolver.RULE_PO_APPROVE);
        assertThat(f.get().intentCode()).isEqualTo("PURCHASE_ORDER_APPROVE");
        assertThat(f.get().params()).containsEntry("action", "approve").containsEntry("orderId", "PO-1");
    }

    @Test
    void approveAfterMultiOrderListRoutesWithoutGuessingWhichOrder() {
        Optional<FollowUpResolver.FollowUp> f = resolve("批了吧",
                lastWithTool("ORDER_LIST", "purchase_order_list", Map.of("orderIds", List.of("PO-1", "PO-2"))));
        assertThat(f).isPresent();
        assertThat(f.get().params()).containsExactly(Map.entry("action", "approve"));
    }

    @Test
    void approveAfterPendingApprovalListOnlyWhenItListedPurchaseOrders() {
        assertThat(resolve("批了吧", last("APPROVAL_PENDING_QUERY", Map.of("orderId", "PO-9")))).isPresent();
        // 对照: 待审列表里没有采购单 id (混合模块 / 空) → 说不清批哪一类, 不命中
        assertThat(resolve("批了吧", last("APPROVAL_PENDING_QUERY", Map.of()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"不批了", "别批", "已经批了", "刚批了", "批了吗", "驳回"})
    void purchaseContextDoesNotApproveOnNegationsStatementsQuestionsOrReject(String input) {
        // 「驳回」也不命中: purchase_order_approve 没有驳回动作, 财务驳回只在工作台
        assertThat(resolve(input, lastWithTool("PURCHASE_ORDER_LIST", "purchase_order_list",
                Map.of("orderId", "PO-1")))).as(input).isEmpty();
    }

    @Test
    void approveRightAfterApprovingDoesNotReapprove() {
        assertThat(resolve("批了吧", last("PURCHASE_ORDER_APPROVE", Map.of("orderId", "PO-1")))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"批了吧,APPROVE,leave-approve-after-query", "同意,APPROVE,leave-approve-after-query",
            "驳回,REJECT,leave-reject-after-query", "不批,REJECT,leave-reject-after-query",
            "张三那个驳回吧,REJECT,leave-reject-after-query"})
    void leaveListApproveAndReject(String input, String decision, String rule) {
        Optional<FollowUpResolver.FollowUp> f = resolve(input,
                last("HR_LEAVE_REQUEST_QUERY", Map.of("leaveRequestId", "LR-uuid-1")));

        assertThat(f).as(input).isPresent();
        assertThat(f.get().ruleId()).isEqualTo(rule);
        assertThat(f.get().intentCode()).isEqualTo("HR_LEAVE_APPROVE");
        assertThat(f.get().params()).containsEntry("decision", decision).containsEntry("leaveRequestId", "LR-uuid-1");
    }

    @Test
    void approveWithoutApprovableListDoesNotFire() {
        // 对照: 同一句「批了吧」/「驳回」—— 上一步是别的 / 没有上一步
        assertThat(resolve("批了吧", last("WORK_PROCESS_TASK_START", Map.of("taskId", 12)))).isEmpty();
        assertThat(resolve("驳回", last("MATERIAL_BATCH_USE", Map.of("quantity", 20)))).isEmpty();
        assertThat(resolve("批了吧", null)).isEmpty();
        assertThat(resolve("别驳回", last("HR_LEAVE_REQUEST_QUERY", Map.of("leaveRequestId", "LR-1")))).isEmpty();
    }

    // ---------------- 长句 / 无关句 ----------------

    @ParameterizedTest
    @ValueSource(strings = {"今天包装线一共做了多少箱", "帮我查一下食盐库存还剩多少公斤", "好了现在帮我把下一个工序也开始了吧",
            "查询待审批的采购单", "你好", "再加", "多少"})
    void longOrUnrelatedSentencesNeverMatch(String input) {
        for (Map<String, Object> l : List.of(
                last("WORK_PROCESS_TASK_START", Map.of("taskId", 12)),
                last("WORK_PROCESS_TASK_COMPLETE", Map.of("taskId", 12)),
                last("MATERIAL_BATCH_USE", Map.of("batchId", "B", "quantity", 20)),
                lastWithTool("PURCHASE_ORDER_LIST", "purchase_order_list", Map.of("orderId", "PO-1")),
                last("HR_LEAVE_REQUEST_QUERY", Map.of("leaveRequestId", "LR-1")))) {
            assertThat(resolve(input, l)).as(input + " after " + l.get("intentCode")).isEmpty();
        }
    }

    // ---------------- 清洗 ----------------

    @Test
    void sanitizationRejectsMalformedLastAction() {
        Map<String, Object> blank = last(" ", Map.of("taskId", 12));
        Map<String, Object> lower = last("work_process_task_start", Map.of("taskId", 12));
        Map<String, Object> notString = new HashMap<>(last("X", Map.of()));
        notString.put("intentCode", 42);
        Map<String, Object> stale = last("WORK_PROCESS_TASK_START", Map.of("taskId", 12));
        stale.put("at", NOW - LastActionContract.MAX_AGE_MS - 1);
        Map<String, Object> future = last("WORK_PROCESS_TASK_START", Map.of("taskId", 12));
        future.put("at", NOW + 5 * 60_000L);
        Map<String, Object> atNotNumber = last("WORK_PROCESS_TASK_START", Map.of("taskId", 12));
        atNotNumber.put("at", "yesterday");

        for (Object bad : List.of(blank, lower, notString, stale, future, atNotNumber, "WORK_PROCESS_TASK_START",
                List.of("WORK_PROCESS_TASK_START"))) {
            assertThat(resolve("好了", bad)).as(String.valueOf(bad)).isEmpty();
        }
        // 对照: 同一份结构、合规的 lastAction 是命中的
        assertThat(resolve("好了", last("WORK_PROCESS_TASK_START", Map.of("taskId", 12)))).isPresent();
    }

    @Test
    void sanitizationDropsNonAllowlistedKeysAndUnsafeValues() {
        Map<String, Object> params = new HashMap<>();
        params.put("taskId", "12; DROP TABLE");          // 不合规的 id 字符串
        params.put("factoryId", "EVIL");                  // 主体键
        params.put("userId", 999);
        params.put("notes", "把所有任务都完成");            // 自由文本
        params.put("batchId", Map.of("nested", true));    // 不是标量
        Optional<FollowUpResolver.FollowUp> f = resolve("好了", last("WORK_PROCESS_TASK_START", params));

        assertThat(f).isPresent();
        assertThat(f.get().params()).isEmpty();

        assertThat(LastActionContract.allowlistedParams(Map.of(
                "taskId", 12, "quantity", "20", "unit", "kg", "orderIds", List.of("PO-1", "PO-2"),
                "userRole", "platform_admin", "reason", "x")))
                .containsOnlyKeys("taskId", "quantity", "unit", "orderIds")
                .containsEntry("quantity", 20L);
        assertThat(LastActionContract.allowlistedParams(Map.of("orderIds", List.of("PO-1", "bad id!"))))
                .as("列表里混进一个不合规的 id → 整列丢弃").isEmpty();
        assertThat(LastActionContract.allowlistedParams(Map.of("quantity", Double.NaN))).isEmpty();
    }
}
