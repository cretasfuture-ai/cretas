package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.inventory.impl.TransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 B10: 自审例外跟随 OA 配置, 不再只给「厂长」这个角色名。
 * 「发起人的岗位是当前节点点名的审批岗位, 且本厂没有第二个够格的人」(一人厂) 就成立 —— 不管这个岗位叫什么;
 * 节点没点名他的岗位、或本厂还有别的有效审批人时仍不放行。厂长的旧行为 (没有别人够格时可自审) 不变。
 */
@DisplayName("OA 自审例外跟随节点配置 (SelfApprovalPolicy / TransferServiceImpl)")
class SelfApprovalOaConfigContractTest {

    private static final String F = "F-SELF";
    private static final Long ACTOR = 500L;

    private ApprovalWorkflowService workflows;
    private UserRepository users;
    private SelfApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        workflows = mock(ApprovalWorkflowService.class);
        users = mock(UserRepository.class);
        when(users.findByFactoryIdAndRoleCode(anyString(), anyString())).thenReturn(List.of());
        policy = new SelfApprovalPolicy(workflows, users);
    }

    private ApprovalWorkflowInstance instance() {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setWorkflowId("wf");
        instance.setCurrentNodeIds(List.of("n1"));
        return instance;
    }

    private void nodeRoles(List<String> roles) {
        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setNodesJson("[]");
        when(workflows.getById(anyString(), anyString())).thenReturn(Optional.of(workflow));
        when(workflows.deserializeNodes(any())).thenReturn(List.of(ApprovalWorkflowNode.builder()
                .id("n1").type("approval").config(Map.of("approverRoles", roles)).build()));
    }

    private static User user(long id, boolean active, String role) {
        User u = new User();
        u.setId(id);
        u.setIsActive(active);
        u.setFactoryId(F);
        u.setRoleCode(role);
        return u;
    }

    @Test
    @DisplayName("一人厂: 节点点名 finance_manager, 发起人就是全厂唯一的财务经理 ⇒ 可自审 (不是厂长也行)")
    void soleHolderOfTheNamedRoleMayApproveOwn() {
        nodeRoles(List.of("finance_manager"));
        when(users.findByFactoryIdAndRoleCode(F, "finance_manager")).thenReturn(List.of(user(ACTOR, true, "finance_manager")));

        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, "finance_manager")).isTrue();
    }

    @Test
    @DisplayName("阴性: 本厂还有另一个有效的财务经理 ⇒ 不能自审 (第二双眼睛照旧)")
    void anotherActiveHolderBlocksTheException() {
        nodeRoles(List.of("finance_manager"));
        when(users.findByFactoryIdAndRoleCode(F, "finance_manager"))
                .thenReturn(List.of(user(ACTOR, true, "finance_manager"), user(501L, true, "finance_manager")));

        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, "finance_manager")).isFalse();
    }

    @Test
    @DisplayName("阴性: 发起人的岗位不在节点点名的岗位里 (销售经理发起、节点只认财务) ⇒ 不放行")
    void roleNotNamedByTheNodeIsNotExcused() {
        nodeRoles(List.of("finance_manager"));

        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, "sales_manager")).isFalse();
    }

    @Test
    @DisplayName("actorRole 缺失时回落查库: 库里是该节点点名的岗位 ⇒ 放行; 账号停用 / 别的厂 ⇒ 不放行")
    void missingActorRoleFallsBackToTheDatabase() {
        nodeRoles(List.of("finance_manager"));
        when(users.findById(ACTOR)).thenReturn(Optional.of(user(ACTOR, true, "finance_manager")));
        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, null)).isTrue();

        when(users.findById(ACTOR)).thenReturn(Optional.of(user(ACTOR, false, "finance_manager")));
        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, null)).isFalse();

        User otherFactory = user(ACTOR, true, "finance_manager");
        otherFactory.setFactoryId("F-OTHER");
        when(users.findById(ACTOR)).thenReturn(Optional.of(otherFactory));
        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, null)).isFalse();
    }

    @Test
    @DisplayName("厂长的旧行为不变: 节点没点名他、但本厂没有别的够格的人 ⇒ 仍可自审")
    void factoryHeadLegacyBehaviourIsPreserved() {
        nodeRoles(List.of("finance_manager"));

        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, "factory_super_admin")).isTrue();
    }

    @Test
    @DisplayName("读不到节点配置时仍只放行厂长 (没有配置可跟随), 其它岗位不因本次改动被放宽")
    void unreadableNodeConfigStillOnlyExcusesTheFactoryHead() {
        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, "finance_manager")).isFalse();
        assertThat(policy.allowsSelfApproval(F, instance(), ACTOR, "factory_super_admin")).isTrue();
    }

    // ── 调拨单提交时的同一条例外 ──

    private boolean transferAllows(List<String> roles, Long initiator) {
        TransferServiceImpl service = new TransferServiceImpl(null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "userRepository", users);
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "allowsSoleApproverSelfApproval", F, roles, initiator));
    }

    @Test
    @DisplayName("调拨: 发起人的岗位在节点 approverRoles 里 (仓储主管一人厂) ⇒ 放行; 不在 ⇒ 不放行; 停用 ⇒ 不放行")
    void transferFollowsTheNodeRoles() {
        when(users.findById(ACTOR)).thenReturn(Optional.of(user(ACTOR, true, "warehouse_manager")));
        assertThat(transferAllows(List.of("Warehouse_Manager"), ACTOR)).as("岗位码忽略大小写").isTrue();
        assertThat(transferAllows(List.of("finance_manager"), ACTOR)).isFalse();
        assertThat(transferAllows(List.of("warehouse_manager"), null)).isFalse();

        when(users.findById(ACTOR)).thenReturn(Optional.of(user(ACTOR, false, "warehouse_manager")));
        assertThat(transferAllows(List.of("warehouse_manager"), ACTOR)).isFalse();
    }
}
