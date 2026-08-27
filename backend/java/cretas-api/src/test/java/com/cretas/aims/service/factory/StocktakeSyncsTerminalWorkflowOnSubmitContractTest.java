package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 契约：提交盘点时，如果审批实例<b>创建即进入终态</b>，盘点单必须照抄它的结论。
 *
 * <h4>🔴 生产实测的卡死（2026-08-28，F006 ST-202608-6B907387）</h4>
 *
 * <p>F006 的库存调整审批流被配成 {@code start → condition(零差异) → end(APPROVED)}。
 * 一张 55 行、零差异的盘点提交后：</p>
 *
 * <pre>
 *   OA 实例   APPROVED, currentNodeIds = []      <- 条件节点工作正常
 *   盘点单    PENDING_APPROVAL, approvedBy=null  <- 被永久钉住
 * </pre>
 *
 * <p>那张单<b>没有任何前进路径</b>：审不了（OA 上没有可操作节点）、
 * 驳不了（直接驳回接口返 410「盘点审批仅能在 OA 审批中心执行」）、
 * 也应用不了（{@code apply} 要求 APPROVED）。</p>
 *
 * <h4>成因</h4>
 *
 * <p>{@code submitForApproval} 里原本是 {@code if (instance.isPresent()) setStatus(PENDING_APPROVAL)}
 * —— <b>从不看实例是不是已经结束了</b>。而把盘点单同步成 APPROVED 的代码只长在
 * {@code transitionNode} 的处理里，需要有人点一次。实例创建即完成时，那个人永远不会出现。</p>
 *
 * <p>⇒ 这是「机制在、没接上」：引擎支持 condition 直连 end，而这个消费方
 * 假设「有实例 == 还要等人审」。配置只是把它<b>暴露</b>了出来，不是配置的错。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("审批实例创建即终态时, 盘点单必须跟着走")
class StocktakeSyncsTerminalWorkflowOnSubmitContractTest {

    private static final String FACTORY = "F006";
    private static final String STOCKTAKE = "ST-1";
    private static final Long SUBMITTER = 1309L;

    @Mock private FactoryStocktakeRepository stocktakeRepo;
    @Mock private WorkflowEngineService workflowEngine;

    @InjectMocks private FactoryStocktakeServiceImpl service;

    private FactoryStocktake stocktake;

    @BeforeEach
    void setUp() {
        stocktake = new FactoryStocktake();
        stocktake.setId(STOCKTAKE);
        stocktake.setFactoryId(FACTORY);
        stocktake.setStocktakeNo("ST-202608-TEST");
        stocktake.setPeriodMonth("2026-08");
        stocktake.setWarehouseId("WH-1");
        stocktake.setStatus(FactoryStocktake.Status.COUNTING);
        stocktake.setInitiatedBy(SUBMITTER);
        stocktake.setItems(new ArrayList<>());
        when(stocktakeRepo.findByIdAndFactoryIdForUpdate(STOCKTAKE, FACTORY))
                .thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // workflowEngine 是 @Autowired(required=false) 的字段, @InjectMocks 走构造器后不再做字段注入。
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "workflowEngine", workflowEngine);
    }

    private void item(String system, String actual) {
        FactoryStocktakeItem it = new FactoryStocktakeItem();
        it.setId("IT-" + stocktake.getItems().size());
        it.setQuantityUnit("kg");
        it.setSystemQty(new BigDecimal(system));
        it.setActualQty(new BigDecimal(actual));
        stocktake.getItems().add(it);
    }

    /** 让引擎返回一个处于指定状态的实例。{@code null} 用来复现 Lombok 那个坑。 */
    private void givenStartedInstance(ApprovalWorkflowInstance.InstanceStatus status) {
        ApprovalWorkflowInstance inst = new ApprovalWorkflowInstance();
        inst.setId("WF-1");
        inst.setStatus(status);
        when(workflowEngine.startWorkflowIfConfigured(
                anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(Optional.of(inst));
    }

    @Test
    @DisplayName("阳性对照: 实例还在跑时, 仍然停在待审批(旧行为不许被改坏)")
    void runningInstanceStillParksAtPendingApproval() {
        item("100", "100");
        givenStartedInstance(ApprovalWorkflowInstance.InstanceStatus.RUNNING);

        service.submitForApproval(STOCKTAKE, FACTORY, SUBMITTER);

        assertThat(stocktake.getStatus())
                .as("实例还在跑就该等人审 —— 这条是下界, 它红了说明修复改坏了正常路径")
                .isEqualTo(FactoryStocktake.Status.PENDING_APPROVAL);
        assertThat(stocktake.getWorkflowInstanceId()).isEqualTo("WF-1");
    }

    @Test
    @DisplayName("🔴 实例创建即 APPROVED(零差异条件节点) → 盘点单必须也 APPROVED, ⛔ 不许卡在待审批")
    void instanceApprovedOnStartMustNotStrandTheStocktake() {
        item("100", "100");
        item("50", "50");
        givenStartedInstance(ApprovalWorkflowInstance.InstanceStatus.APPROVED);

        service.submitForApproval(STOCKTAKE, FACTORY, SUBMITTER);

        assertThat(stocktake.getStatus())
                .as("""
                        实例已经 APPROVED 而盘点单停在 PENDING_APPROVAL —— 这张单从此
                        审不了(OA 无可操作节点)、驳不了(直接驳回返 410)、应用不了(需 APPROVED)。
                        生产实测: F006 ST-202608-6B907387。""")
                .isEqualTo(FactoryStocktake.Status.APPROVED);
        assertThat(stocktake.getApprovedAt())
                .as("APPROVED 却没有审批时间, 下游对账读不出这单什么时候过的")
                .isNotNull();
        assertThat(stocktake.getWorkflowInstanceId()).isEqualTo("WF-1");
    }

    @Test
    @DisplayName("实例创建即 REJECTED → 盘点单跟着 REJECTED")
    void instanceRejectedOnStartIsMirrored() {
        item("100", "100");
        givenStartedInstance(ApprovalWorkflowInstance.InstanceStatus.REJECTED);

        service.submitForApproval(STOCKTAKE, FACTORY, SUBMITTER);

        assertThat(stocktake.getStatus()).isEqualTo(FactoryStocktake.Status.REJECTED);
    }

    @Test
    @DisplayName("⚠️ status 为 null 时按「还要等人审」处理 —— Lombok @Builder.Default 会把字段初始化器搬走")
    void nullStatusIsTreatedAsStillRunning() {
        item("100", "100");
        givenStartedInstance(null);

        service.submitForApproval(STOCKTAKE, FACTORY, SUBMITTER);

        assertThat(stocktake.getStatus())
                .as("""
                        `new ApprovalWorkflowInstance()` 拿到的 status 是 null(不是 RUNNING) ——
                        @Builder.Default 把初始化器搬进了 builder。若这里用穷举 else 兜底,
                        null 会掉进「配置错误」那一支, 把一批与本改动无关的用例打红。""")
                .isEqualTo(FactoryStocktake.Status.PENDING_APPROVAL);
    }
}
