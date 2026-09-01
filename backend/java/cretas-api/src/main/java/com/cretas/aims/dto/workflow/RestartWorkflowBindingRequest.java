package com.cretas.aims.dto.workflow;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 缺陷 27 恢复入口的请求体 —— 把因「绑定的审批流定义被改过」而卡死的在途实例
 * 作废并按当前定义重新发起。
 *
 * <p>形状刻意对齐 {@code RecoverPurchaseApprovalRequest}: 两者都是管理员用来
 * 修复在途单据的受限操作, 都要求「明确确认 + 写清原因」, 而不是一个点一下就生效的按钮。
 *
 * <p>⚠️ 与那一个的区别: 这里<b>不</b>要求幂等键。那一条恢复的是「已提交但没有实例」,
 * 重放会凭空多造一个实例, 所以必须靠幂等键挡住; 本操作的前置条件是
 * 「存在 RUNNING 实例且其绑定摘要已过期」—— 第一次执行之后新实例绑的就是当前摘要,
 * 重放会被 {@code WORKFLOW_REBIND_NOT_STALE} 直接拒掉, 前置条件本身就是幂等闸。
 */
@Data
public class RestartWorkflowBindingRequest {

    @NotBlank(message = "必须填写恢复原因")
    @Size(min = 5, max = 500, message = "恢复原因长度必须为5到500个字符")
    private String reason;

    @AssertTrue(message = "必须明确确认：该单据的审批将按当前审批流从头重走")
    private boolean confirm;
}
