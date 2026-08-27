package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「发起人能否审批自己的单」的判定语义。
 *
 * <p>背景: 六膳门(单人工厂)的 OA 节点把唯一的 factory_super_admin(1638) 同时配成
 * approverUserIds 和唯一授权角色, 而他正是发起人 —— 旧实现无条件禁止自审, 导致该单
 * 结构性无法审批, 且提示让用户"找其他审批人"而那个人不存在。
 */
class SelfApprovalPolicyTest {

    private ApprovalWorkflowService approvalWorkflowService;
    private UserRepository userRepository;
    private SelfApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        approvalWorkflowService = mock(ApprovalWorkflowService.class);
        userRepository = mock(UserRepository.class);
        when(userRepository.findByFactoryIdAndRoleCode(anyString(), anyString()))
                .thenReturn(List.of());
        policy = new SelfApprovalPolicy(approvalWorkflowService, userRepository);
    }

    private ApprovalWorkflowInstance instanceAtNode(String nodeId) {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setWorkflowId("wf-1");
        instance.setCurrentNodeIds(List.of(nodeId));
        return instance;
    }

    private void givenNodeApprovers(String nodeId, List<Object> approverUserIds) {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setNodesJson("[]");
        when(approvalWorkflowService.getById(anyString(), anyString()))
                .thenReturn(Optional.of(workflow));
        ApprovalWorkflowNode node = ApprovalWorkflowNode.builder()
                .id(nodeId)
                .type("approval")
                .config(Map.of("approverUserIds", approverUserIds))
                .build();
        when(approvalWorkflowService.deserializeNodes(any())).thenReturn(List.of(node));
    }

    @Test
    @DisplayName("节点显式点名发起人时允许自审")
    void explicitlyNamedInitiatorMayApprove() {
        givenNodeApprovers("admin_approval", List.of(1638));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isTrue();
    }

    @Test
    @DisplayName("工厂超管即使没被点名也允许自审")
    void factorySuperAdminMayApproveEvenWhenNotNamed() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "factory_super_admin"))
                .isTrue();
    }

    @Test
    @DisplayName("既未点名又非超管则不允许自审")
    void neitherNamedNorSuperAdminIsRejected() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("点名以字符串形式配置时同样识别")
    void namedApproverAsStringIsRecognised() {
        givenNodeApprovers("admin_approval", List.of("1638"));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isTrue();
    }

    @Test
    @DisplayName("实例没有当前节点时不允许自审")
    void instanceWithoutCurrentNodeIsRejected() {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setWorkflowId("wf-1");
        instance.setCurrentNodeIds(List.of());
        assertThat(policy.allowsSelfApproval("LIUSHANMEN", instance, 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("非 approval 类型的节点不构成点名")
    void nonApprovalNodeIsNotAnExplicitNaming() {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setNodesJson("[]");
        when(approvalWorkflowService.getById(anyString(), anyString()))
                .thenReturn(Optional.of(workflow));
        ApprovalWorkflowNode node = ApprovalWorkflowNode.builder()
                .id("start")
                .type("start")
                .config(Map.of("approverUserIds", List.of(1638)))
                .build();
        when(approvalWorkflowService.deserializeNodes(any())).thenReturn(List.of(node));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("start"), 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("超管判定不依赖工作流服务 —— 服务缺席时仍放行超管")
    void superAdminDecisionDoesNotNeedWorkflowService() {
        SelfApprovalPolicy noServicePolicy = new SelfApprovalPolicy(null, userRepository);
        assertThat(noServicePolicy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "factory_super_admin"))
                .isTrue();
    }

    @Test
    @DisplayName("两个服务都缺席且非超管时保守拒绝")
    void missingCollaboratorsFallBackToRejection() {
        SelfApprovalPolicy noServicePolicy = new SelfApprovalPolicy(null, null);
        assertThat(noServicePolicy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "sales_manager"))
                .isFalse();
    }

    @Test
    @DisplayName("角色码大小写不同也认 —— 沿用调拨单原有的 equalsIgnoreCase")
    void roleCodeIsCaseInsensitive() {
        givenNodeApprovers("admin_approval", List.of(9999));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, "FACTORY_SUPER_ADMIN"))
                .isTrue();
    }

    @Test
    @DisplayName("actorRole 缺失时回落查库, 不让缺失的入参把例外吞掉")
    void missingActorRoleFallsBackToDatabase() {
        givenNodeApprovers("admin_approval", List.of(9999));
        User superAdmin = new User();
        superAdmin.setId(1638L);
        superAdmin.setIsActive(true);
        when(userRepository.findByFactoryIdAndRoleCode("LIUSHANMEN", "factory_super_admin"))
                .thenReturn(List.of(superAdmin));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, null))
                .isTrue();
    }

    @Test
    @DisplayName("非超管且 actorRole 缺失时查库也找不到 —— 不放行")
    void nonSuperAdminWithMissingRoleIsRejected() {
        givenNodeApprovers("admin_approval", List.of(9999));
        User superAdmin = new User();
        superAdmin.setId(1638L);
        superAdmin.setIsActive(true);
        when(userRepository.findByFactoryIdAndRoleCode("LIUSHANMEN", "factory_super_admin"))
                .thenReturn(List.of(superAdmin));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 2001L, null))
                .isFalse();
    }

    @Test
    @DisplayName("匿名 actor 永远不被当作超管")
    void anonymousActorIsNeverSuperAdmin() {
        givenNodeApprovers("admin_approval", List.of(1638));
        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), null, "factory_super_admin"))
                .isFalse();
    }

    @Test
    @DisplayName("库里的超管账号已停用则不放行")
    void inactiveSuperAdminIsNotHonoured() {
        givenNodeApprovers("admin_approval", List.of(9999));
        User disabled = new User();
        disabled.setId(1638L);
        disabled.setIsActive(false);
        when(userRepository.findByFactoryIdAndRoleCode("LIUSHANMEN", "factory_super_admin"))
                .thenReturn(List.of(disabled));

        assertThat(policy.allowsSelfApproval(
                "LIUSHANMEN", instanceAtNode("admin_approval"), 1638L, null))
                .isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 2026-08-27 收窄: 超管例外仅当本厂再无第二个够格审批人时才成立
    //
    // ⚠️ 建这一组之前, 全文件 approverRoles 出现 0 次 —— 上面每一条超管用例走的都是
    //    「节点没有 approverRoles ⇒ 读不到 ⇒ 放行」那条**保留**分支。
    //    也就是说收窄之后它们照样全绿, 而**没有一条**碰到被改的那段逻辑。
    // ⛔ 别删这段注释: 它解释了为什么下面这四条不是重复覆盖。
    // ─────────────────────────────────────────────────────────────

    /** 节点按**角色**授权(而不是点名) —— 收窄后的判据读的就是这个。 */
    private void givenNodeApproverRoles(String nodeId, List<String> roles) {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setNodesJson("[]");
        when(approvalWorkflowService.getById(anyString(), anyString()))
                .thenReturn(Optional.of(workflow));
        ApprovalWorkflowNode node = ApprovalWorkflowNode.builder()
                .id(nodeId)
                .type("approval")
                .config(Map.of("approverRoles", roles))
                .build();
        when(approvalWorkflowService.deserializeNodes(any())).thenReturn(List.of(node));
    }

    private User user(long id, boolean active) {
        User u = new User();
        u.setId(id);
        u.setIsActive(active);
        return u;
    }

    @Test
    @DisplayName("🔴 本厂还有别的活跃审批人时, 超管不能再自审")
    void superAdminIsDeniedWhenAnotherActiveApproverExists() {
        givenNodeApproverRoles("approval_boss", List.of("finance_manager"));
        when(userRepository.findByFactoryIdAndRoleCode("F006", "finance_manager"))
                .thenReturn(List.of(user(2001L, true)));

        assertThat(policy.allowsSelfApproval(
                "F006", instanceAtNode("approval_boss"), 1309L, "factory_super_admin"))
                .as("有第二个人够格审, 就不该再走「单人工厂」那条例外")
                .isFalse();
    }

    @Test
    @DisplayName("本厂确实没有别人够格时, 超管仍可自审 —— ⛔ 不许重现「一个多月批不掉」")
    void superAdminStillAllowedWhenNobodyElseCouldApprove() {
        givenNodeApproverRoles("approval_boss", List.of("finance_manager"));
        // 查库返回空 = 本厂没有活跃的 finance_manager
        assertThat(policy.allowsSelfApproval(
                "F006", instanceAtNode("approval_boss"), 1309L, "factory_super_admin"))
                .as("""
                        这条是收窄的**下界**: 单人工厂里超管必须能推进自己的单。
                        spec 记着实测代价 —— 某工厂曾因此一个多月没走通过一次调拨审批, 单子全卡在草稿。""")
                .isTrue();
    }

    @Test
    @DisplayName("停用的账号不算「还有别人」—— 否则会把工厂锁死")
    void inactiveOtherApproverDoesNotCount() {
        givenNodeApproverRoles("approval_boss", List.of("finance_manager"));
        when(userRepository.findByFactoryIdAndRoleCode("F006", "finance_manager"))
                .thenReturn(List.of(user(2001L, false)));

        assertThat(policy.allowsSelfApproval(
                "F006", instanceAtNode("approval_boss"), 1309L, "factory_super_admin"))
                .as("停用账号审不了单, 拿它当「还有别人」等于把工厂锁死")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 F006 实况: 角色里只有发起人自己 —— 他不算「另一个人」")
    void theActorHimselfIsNotAnotherApprover() {
        // 实测 2026-08-27: F006 有 9 个 factory_super_admin, 只有 1309 是活的, 其余 8 个停用。
        // 若节点仍按 factory_super_admin 授权, 那么「够格的人」就只有发起人本人。
        givenNodeApproverRoles("approval_boss", List.of("factory_super_admin"));
        when(userRepository.findByFactoryIdAndRoleCode("F006", "factory_super_admin"))
                .thenReturn(List.of(user(1309L, true), user(1610L, false), user(1611L, false)));

        assertThat(policy.allowsSelfApproval(
                "F006", instanceAtNode("approval_boss"), 1309L, "factory_super_admin"))
                .as("""
                        ⚠️ 这条同时是给配置者的警告: 只把节点配成 approverRoles=[factory_super_admin]
                        **不足以**实现「要求他审」—— 够格的人只有发起人自己, 例外照样成立。
                        要真正分离职责, 节点必须授权给本厂另有活跃账号的角色。""")
                .isTrue();
    }
}
