package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 「发起人能否审批自己的单」的唯一判定处。
 *
 * <p>此前这条规则在销售 / 采购 / 调拨三处各自承载, 且行为并不一致: 采购单有
 * {@code isExplicitCurrentNodeApprover} 私有例外, 销售单与调拨单则无条件禁止。
 * 因为那个方法是 private 且全仓仅此一份, 另外两处想复用也复用不了 —— 于是同一条
 * 规则长出了两种行为。本类把它提出来, 三处统一委托。
 *
 * <p><b>放行条件(满足其一)</b>:
 * <ol>
 *   <li>OA 节点在 {@code approverUserIds} 里<b>显式点名</b>了这个人 —— 配置者的明确意图;</li>
 *   <li>这个人是 {@code factory_super_admin}, <b>并且本厂再没有第二个人够格审当前这个节点</b>
 *       —— 单人工厂里超管必须能推进自己的单, 否则单据结构性卡死。</li>
 * </ol>
 *
 * <h4>🔴 2026-08-27 收窄: 第 2 条此前是<b>无条件</b>的</h4>
 *
 * <p>那一条的<b>自述理由</b>一直是「单人工厂」, 但<b>实现</b>不看厂里有没有别人 ——
 * 于是它在<b>有</b>其他合格审批人的工厂里照样放行。文与行为不符, 而且是往松的方向不符。</p>
 *
 * <p>生产实测(2026-08-27)：</p>
 * <pre>
 *   F006       调拨 9 单, 已批准 6 单, 6/6 是 f006_admin 自己批自己 (requested_by == approved_by)
 *   LIUSHANMEN 调拨 1 单, 同样自审 (1638 -> 1638)
 * </pre>
 *
 * <p>⚠️ LIUSHANMEN 那一单是<b>阳性对照</b>: 它<b>配了并发布了</b>
 * {@code INVENTORY_TRANSFER_APPROVAL}, 照样自审 ⇒ <b>「没配审批流」不是成因</b>,
 * 光配置也不会改变任何行为。成因就是这条无条件例外。</p>
 *
 * <p><b>收窄的边界(有意为之, 不是遗漏)</b>: 只有在<b>能读到当前节点的
 * {@code approverRoles}</b> 时才收窄。读不到(没有实例 / 没有工作流 / 配置损坏)一律
 * <b>沿用旧的放行行为</b> —— 未配审批流的工厂占多数, 在那里改成拒绝
 * 等于把它们的单据全部锁死, 那正是这条例外当初要防的事。
 * ⇒ 本次改动<b>只在配了审批流的工厂上生效</b>。</p>
 *
 * <p><b>⚠️ 刻意不走本类的三处</b>(它们是独立业务语义, 不是本规则的实例):
 * <ul>
 *   <li>{@code FactoryStocktakeServiceImpl} 两处 {@code STOCKTAKE_SELF_APPROVAL_FORBIDDEN} ——
 *       用 409 而非 403, <b>仅当存在盘盈/盘亏时</b>才拦, 且判据含录入人与提交人;</li>
 *   <li>{@code ReportReversalServiceImpl} 的 {@code SELF_APPROVAL_FORBIDDEN} —— 撤回冲销, 属红线。</li>
 * </ul>
 * 若将来要把它们并进来, 先改 spec
 * {@code docs/superpowers/specs/2026-08-01-oa-self-approval-and-budget-design.md} §1,
 * 并同步 {@code SelfApprovalCarrierContractTest}。
 */
@Component
public class SelfApprovalPolicy {

    private static final String FACTORY_SUPER_ADMIN = "factory_super_admin";
    private static final String APPROVAL_NODE_TYPE = "approval";
    private static final String APPROVER_USER_IDS = "approverUserIds";
    private static final String APPROVER_ROLES = "approverRoles";

    @Nullable
    private final ApprovalWorkflowService approvalWorkflowService;

    @Nullable
    private final UserRepository userRepository;

    public SelfApprovalPolicy(@Nullable ApprovalWorkflowService approvalWorkflowService,
                              @Nullable UserRepository userRepository) {
        this.approvalWorkflowService = approvalWorkflowService;
        this.userRepository = userRepository;
    }

    /**
     * @return {@code true} 表示「actor 虽然就是发起人, 但允许他审批」。
     *         调用方仍需自行判断 actor 是否等于发起人 —— 本方法只回答「例外成不成立」。
     */
    public boolean allowsSelfApproval(String factoryId,
                                      ApprovalWorkflowInstance instance,
                                      Long actorId,
                                      String actorRole) {
        return isExplicitCurrentNodeApprover(factoryId, instance, actorId)
                || isSoleEligibleFactorySuperAdmin(factoryId, instance, actorId, actorRole);
    }

    /**
     * 超管例外 —— <b>仅当本厂再没有第二个人够格审当前这个节点</b>。
     *
     * <p>⚠️ 「够格」用的是<b>审批引擎自己那条判据</b>: 节点 {@code config.approverRoles}
     * 含此人的 {@code roleCode}（见 {@code WorkflowEngineServiceImpl#canTransition} 第 4 条）。
     * ⛔ 不要在这里另写一套「谁算合格审批人」—— 两份判据一定会漂。</p>
     *
     * <p><b>读不到节点角色时一律放行</b>（沿用收窄之前的行为）。这不是偷懒:</p>
     * <ul>
     *   <li>没有实例 / 没有工作流 —— 绝大多数工厂根本没配审批流，在那里改成拒绝
     *       等于把它们的单据全部锁死；</li>
     *   <li>配置损坏 / 反序列化失败 —— 同理。</li>
     * </ul>
     * <p>⇒ 本次收窄<b>只在配了审批流的工厂上生效</b>，这一点是有意的。</p>
     */
    private boolean isSoleEligibleFactorySuperAdmin(String factoryId,
                                                    ApprovalWorkflowInstance instance,
                                                    Long actorId,
                                                    String actorRole) {
        if (!isFactorySuperAdmin(factoryId, actorId, actorRole)) {
            return false;
        }
        List<String> approverRoles = currentNodeApproverRoles(factoryId, instance);
        if (approverRoles.isEmpty()) {
            // 读不到节点角色 —— 沿用旧行为放行, 见本方法 javadoc。
            return true;
        }
        return !anotherActiveUserCouldApprove(factoryId, approverRoles, actorId);
    }

    /**
     * 本厂是否还有<b>另一个</b>活跃账号的角色在 {@code approverRoles} 里。
     *
     * <p>⚠️ 只认 {@code isActive} 的账号 —— 停用的账号审不了单, 拿它当「还有别人」
     * 会把工厂锁死。（实测 F006 有 9 个 {@code factory_super_admin}, 但<b>只有 1 个是活的</b>,
     * 另外 8 个是历史测试账号。）</p>
     */
    private boolean anotherActiveUserCouldApprove(String factoryId,
                                                  List<String> approverRoles,
                                                  Long actorId) {
        if (userRepository == null) {
            return false;
        }
        for (String role : approverRoles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            boolean found = userRepository.findByFactoryIdAndRoleCode(factoryId, role).stream()
                    .anyMatch(user -> Boolean.TRUE.equals(user.getIsActive())
                            && !Objects.equals(user.getId(), actorId));
            if (found) {
                return true;
            }
        }
        return false;
    }

    /** 当前 active approval 节点配置的 {@code approverRoles}; 读不到返回空列表。 */
    private List<String> currentNodeApproverRoles(String factoryId,
                                                  ApprovalWorkflowInstance instance) {
        ApprovalWorkflowNode node = currentApprovalNode(factoryId, instance);
        if (node == null || node.getConfig() == null) {
            return List.of();
        }
        Object roles = node.getConfig().get(APPROVER_ROLES);
        if (!(roles instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object role : iterable) {
            if (role != null) {
                out.add(String.valueOf(role).trim());
            }
        }
        return out;
    }

    /**
     * 发起人是否为本厂工厂总监 —— 自审批例外判据之一。
     *
     * <p>实现取自 {@code TransferServiceImpl#isFactorySuperAdmin}(调拨单原有的那半例外)。
     * 注意两个细节, 都是有意为之:
     * <ul>
     *   <li>用 {@code equalsIgnoreCase} 而非 {@code equals};</li>
     *   <li>{@code actorRole} 为空时<b>回落查库</b> —— 该入参由调用方传入, 可能缺失,
     *       以库里的角色为准, 不让缺失的入参把例外吞掉。</li>
     * </ul>
     * 并且只认 {@code isActive} 的账号。
     */
    private boolean isFactorySuperAdmin(String factoryId, Long userId, String actorRole) {
        if (userId == null) {
            return false;
        }
        if (FACTORY_SUPER_ADMIN.equalsIgnoreCase(actorRole)) {
            return true;
        }
        return userRepository != null
                && userRepository.findByFactoryIdAndRoleCode(factoryId, FACTORY_SUPER_ADMIN).stream()
                .anyMatch(user -> Boolean.TRUE.equals(user.getIsActive())
                        && Objects.equals(user.getId(), userId));
    }

    /**
     * A workflow may intentionally name the initiator as the approver for a single-user factory.
     * Role membership alone is not enough to bypass separation of duties: the active node must
     * explicitly contain the actor in {@code approverUserIds}.
     */
    private boolean isExplicitCurrentNodeApprover(String factoryId,
                                                  ApprovalWorkflowInstance instance,
                                                  Long actorId) {
        if (actorId == null) {
            return false;
        }
        ApprovalWorkflowNode currentNode = currentApprovalNode(factoryId, instance);
        if (currentNode == null || currentNode.getConfig() == null) {
            return false;
        }
        Object configuredApprovers = currentNode.getConfig().get(APPROVER_USER_IDS);
        if (!(configuredApprovers instanceof Iterable<?> approvers)) {
            return false;
        }
        for (Object configuredApprover : approvers) {
            if (configuredApprover instanceof Number number
                    && actorId.equals(number.longValue())) {
                return true;
            }
            if (configuredApprover != null
                    && actorId.toString().equals(String.valueOf(configuredApprover).trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前 active approval 节点 —— <b>两条例外判据共用的唯一一份</b>。
     *
     * <p>此前这段查找只长在 {@link #isExplicitCurrentNodeApprover} 里面。收窄超管例外时
     * 也要读同一个节点，⛔ 没有复制第二份 —— 同一个东西有两份，它一定会漂。</p>
     *
     * <p>Phase 1 简化沿用引擎的口径：取 {@code currentNodeIds[0]}
     * （mirror {@code WorkflowEngineServiceImpl#transitionNode}）。</p>
     *
     * @return 当前审批节点；实例/工作流/节点任一读不到，或它不是 {@code approval} 型，均返回 {@code null}
     */
    @Nullable
    private ApprovalWorkflowNode currentApprovalNode(String factoryId,
                                                     ApprovalWorkflowInstance instance) {
        if (approvalWorkflowService == null
                || instance == null
                || instance.getCurrentNodeIds() == null
                || instance.getCurrentNodeIds().isEmpty()) {
            return null;
        }
        ApprovalWorkflow workflow = approvalWorkflowService
                .getById(factoryId, instance.getWorkflowId())
                .orElse(null);
        if (workflow == null) {
            return null;
        }
        String currentNodeId = instance.getCurrentNodeIds().get(0);
        ApprovalWorkflowNode currentNode = approvalWorkflowService
                .deserializeNodes(workflow.getNodesJson()).stream()
                .filter(node -> currentNodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
        if (currentNode == null || !APPROVAL_NODE_TYPE.equalsIgnoreCase(currentNode.getType())) {
            return null;
        }
        return currentNode;
    }
}
