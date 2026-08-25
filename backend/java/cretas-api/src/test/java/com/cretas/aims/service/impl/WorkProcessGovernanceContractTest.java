package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.WorkProcessGovernanceAudit;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessGovernanceAuditRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工序重复治理 (governDuplicates) 的行为契约。
 *
 * <p><b>为什么新建这个文件而不是加进 {@code WorkProcessServiceImplTest}</b>：
 * 后者类名不落在 CI 的窄选择器
 * ({@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest,*ContractTest})
 * 里，写进去只会被编译、不会被执行 —— 见 AGENTS.md「闸的命名约定（决定它跑不跑）」。
 * 本类以 {@code ContractTest} 结尾，因此 push 上真的会跑。</p>
 *
 * <p><b>它守的是什么</b>：{@code POST /duplicates/govern} 的 Swagger 摘要写着
 * 「仅影响未来选择，<b>不迁移已有引用</b>」。这是这条路径对用户唯一的实质承诺 ——
 * 治理重复工序不会动既有 Workflow / SKU / 报工任务的引用。
 * 该承诺此前<b>没有任何测试</b>：{@code WorkProcessServiceImplTest} 只是为了满足
 * {@code @InjectMocks} 而 mock 了 {@code WorkProcessGovernanceAuditRepository}，
 * 一条 governance 用例都没有（2026-08-25 实测 grep: 0 处调用 {@code governDuplicates}）。</p>
 *
 * <p><b>生产实测背景 (2026-08-25, cretas_prod_db 只读)</b>：F006 共 190 条存活工序，
 * {@code merged_into_id} 非空的是 <b>0 条</b> —— 这条治理路径从上线到现在一次都没被用过。
 * 重复组确实存在：焯水×4 / 熟制(卤制)×2 / 修油×2，共 3 组 8 行。
 * 也就是说这段逻辑既<b>没有生产流量</b>也<b>没有测试覆盖</b>，改动时没有任何东西会红。</p>
 */
@DisplayName("工序重复治理契约 (governDuplicates)")
@ExtendWith(MockitoExtension.class)
class WorkProcessGovernanceContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String MASTER_ID = "wp-master";
    private static final String DUP_ID = "wp-dup";
    private static final String IDEMPOTENCY_KEY = "gov-key-001";

    @Mock
    private WorkProcessRepository workProcessRepository;
    @Mock
    private WorkProcessGovernanceAuditRepository governanceAuditRepository;
    @Mock
    private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock
    private ProductProcessWorkflowRepository workflowRepository;
    @Mock
    private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private com.cretas.aims.repository.bom.BomSeasoningItemRepository bomSeasoningItemRepository;
    @Mock
    private com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository bomProcessInjectionConfigRepository;

    @InjectMocks
    private WorkProcessServiceImpl workProcessService;

    /** 同名同类别、关键配置一致 —— 构成一个可自动合并的重复组。 */
    private WorkProcess process(String id, boolean active) {
        return WorkProcess.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .processName("焯水")
                .processCategory("加工")
                .unit("kg")
                .estimatedMinutes(30)
                .sortOrder(1)
                .isActive(active)
                .build();
    }

    private WorkProcessDTO.GovernanceRequest request(WorkProcessDTO.GovernanceMode mode) {
        return WorkProcessDTO.GovernanceRequest.builder()
                .masterProcessId(MASTER_ID)
                .duplicateProcessIds(List.of(DUP_ID))
                .mode(mode)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .reason("重复录入")
                .build();
    }

    private void givenLockedGroup(WorkProcess master, WorkProcess duplicate) {
        when(governanceAuditRepository.findByFactoryIdAndIdempotencyKey(FACTORY_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(workProcessRepository.lockByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                .thenReturn(List.of(master, duplicate));
    }

    @Nested
    @DisplayName("核心承诺: 不迁移已有引用")
    class DoesNotMigrateExistingReferences {

        /**
         * 这是本文件存在的首要理由。端点摘要承诺「不迁移已有引用」，
         * 而验证它的方式不是读代码，是断言治理过程<b>根本没碰</b>那三个引用仓储。
         */
        @Test
        @DisplayName("MERGE 治理不读写 SKU/工作流/报工任务的引用仓储")
        void mergeDoesNotTouchReferenceRepositories() {
            WorkProcess master = process(MASTER_ID, true);
            WorkProcess duplicate = process(DUP_ID, true);
            givenLockedGroup(master, duplicate);

            workProcessService.governDuplicates(FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE));

            // 既有引用一律不动: 这三个仓储在整条治理路径上不应有任何交互。
            verifyNoInteractions(productWorkProcessRepository);
            verifyNoInteractions(workflowRepository);
            verifyNoInteractions(workProcessTaskRepository);
        }
    }

    @Nested
    @DisplayName("两种治理模式的落库差异")
    class GovernanceModes {

        @Test
        @DisplayName("MERGE: 重复项停用并写 mergedInto*, 主工序保持启用")
        void mergeSetsMergePointerAndKeepsMasterActive() {
            WorkProcess master = process(MASTER_ID, true);
            WorkProcess duplicate = process(DUP_ID, true);
            givenLockedGroup(master, duplicate);

            WorkProcessDTO.GovernanceResult result =
                    workProcessService.governDuplicates(FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE));

            assertThat(duplicate.getIsActive()).isFalse();
            assertThat(duplicate.getMergedIntoId()).isEqualTo(MASTER_ID);
            assertThat(duplicate.getMergedAt()).isNotNull();
            assertThat(duplicate.getMergedBy()).isNotBlank();
            assertThat(duplicate.getGovernanceReason()).isEqualTo("重复录入");
            assertThat(master.getIsActive()).isTrue();
            assertThat(master.getMergedIntoId()).isNull();

            assertThat(result.isReplayed()).isFalse();
            assertThat(result.getMasterProcessId()).isEqualTo(MASTER_ID);
            assertThat(result.getGovernedProcessIds()).containsExactly(DUP_ID);
        }

        /**
         * 阴性对照: DEACTIVATE_OTHERS 与 MERGE 的区别<b>只在</b> mergedInto* 三个字段上。
         * 若哪天有人把 mergedIntoId 的写入挪出 mode 判断, 这条会红而上面那条不会。
         */
        @Test
        @DisplayName("DEACTIVATE_OTHERS: 停用但不写 mergedInto* (与 MERGE 的唯一差别)")
        void deactivateOthersDoesNotSetMergePointer() {
            WorkProcess master = process(MASTER_ID, true);
            WorkProcess duplicate = process(DUP_ID, true);
            givenLockedGroup(master, duplicate);

            workProcessService.governDuplicates(
                    FACTORY_ID, request(WorkProcessDTO.GovernanceMode.DEACTIVATE_OTHERS));

            assertThat(duplicate.getIsActive()).isFalse();
            assertThat(duplicate.getMergedIntoId()).isNull();
            assertThat(duplicate.getMergedAt()).isNull();
            assertThat(duplicate.getMergedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("幂等")
    class Idempotency {

        @Test
        @DisplayName("同键同请求重放: 返回 replayed=true 且不再写任何库")
        void replayReturnsPreviousResultWithoutWriting() {
            WorkProcessGovernanceAudit audit = WorkProcessGovernanceAudit.builder()
                    .id("audit-1")
                    .factoryId(FACTORY_ID)
                    .idempotencyKey(IDEMPOTENCY_KEY)
                    .mode(WorkProcessDTO.GovernanceMode.MERGE)
                    .masterProcessId(MASTER_ID)
                    .governedProcessIds(DUP_ID)
                    .operator("tester")
                    .build();
            when(governanceAuditRepository.findByFactoryIdAndIdempotencyKey(FACTORY_ID, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.of(audit));

            WorkProcessDTO.GovernanceResult result =
                    workProcessService.governDuplicates(FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE));

            assertThat(result.isReplayed()).isTrue();
            assertThat(result.getMasterProcessId()).isEqualTo(MASTER_ID);
            // 重放必须是纯读: 不加锁、不保存、不再写审计。
            verify(workProcessRepository, never()).lockByFactoryIdAndIdIn(anyString(), any());
            verify(workProcessRepository, never()).saveAll(any());
            verify(governanceAuditRepository, never()).save(any());
        }

        @Test
        @DisplayName("同键不同请求: 409, 不静默覆盖")
        void reusedKeyWithDifferentPayloadIsRejected() {
            WorkProcessGovernanceAudit audit = WorkProcessGovernanceAudit.builder()
                    .id("audit-1")
                    .factoryId(FACTORY_ID)
                    .idempotencyKey(IDEMPOTENCY_KEY)
                    .mode(WorkProcessDTO.GovernanceMode.MERGE)
                    .masterProcessId("wp-someone-else")
                    .governedProcessIds(DUP_ID)
                    .operator("tester")
                    .build();
            when(governanceAuditRepository.findByFactoryIdAndIdempotencyKey(FACTORY_ID, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.of(audit));

            assertThatThrownBy(() -> workProcessService.governDuplicates(
                    FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("幂等键");

            verify(workProcessRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("拒绝非法治理")
    class Guards {

        @Test
        @DisplayName("跨重复组 (名称/类别不同): 409")
        void refusesToGovernAcrossDuplicateGroups() {
            WorkProcess master = process(MASTER_ID, true);
            WorkProcess otherGroup = WorkProcess.builder()
                    .id(DUP_ID)
                    .factoryId(FACTORY_ID)
                    .processName("包装")          // 不同名 -> 不同重复组
                    .processCategory("后处理")
                    .unit("件")
                    .isActive(true)
                    .build();
            givenLockedGroup(master, otherGroup);

            assertThatThrownBy(() -> workProcessService.governDuplicates(
                    FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("同一名称和类别");

            verify(workProcessRepository, never()).saveAll(any());
        }

        /**
         * MERGE 才做关键配置一致性检查; DEACTIVATE_OTHERS 不做。
         * 这条同时是上面 modes 用例的阴性对照 —— 它证明 MERGE 分支确实多守了一层。
         */
        @Test
        @DisplayName("MERGE + 关键动作配置不一致: 409, 不自动合并")
        void refusesMergeWhenCriticalConfigurationDiffers() {
            WorkProcess master = process(MASTER_ID, true);
            master.setStandardYieldMin(new BigDecimal("0.80"));
            WorkProcess duplicate = process(DUP_ID, true);
            duplicate.setStandardYieldMin(new BigDecimal("0.55"));   // 关键配置不同
            givenLockedGroup(master, duplicate);

            assertThatThrownBy(() -> workProcessService.governDuplicates(
                    FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("关键动作配置差异");

            verify(workProcessRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("锁到的行数不足 (有 id 不属于本厂): 404")
        void refusesWhenSomeProcessesAreNotInThisFactory() {
            when(governanceAuditRepository.findByFactoryIdAndIdempotencyKey(FACTORY_ID, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.empty());
            // 请求了 master + dup 两条, 只锁到一条 -> 另一条不存在或跨租户
            when(workProcessRepository.lockByFactoryIdAndIdIn(eq(FACTORY_ID), any()))
                    .thenReturn(List.of(process(MASTER_ID, true)));

            assertThatThrownBy(() -> workProcessService.governDuplicates(
                    FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不属于当前工厂");

            verify(workProcessRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("mode 缺失: 400")
        void refusesWhenModeMissing() {
            WorkProcessDTO.GovernanceRequest req = WorkProcessDTO.GovernanceRequest.builder()
                    .masterProcessId(MASTER_ID)
                    .duplicateProcessIds(List.of(DUP_ID))
                    .mode(null)
                    .idempotencyKey(IDEMPOTENCY_KEY)
                    .build();

            assertThatThrownBy(() -> workProcessService.governDuplicates(FACTORY_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("治理方式");
        }

        /**
         * 只把 master 自己列进 duplicateProcessIds 时, 过滤后为空 —— 必须 400,
         * ⛔ 不能当成「治理了 0 条」静默成功: 那会写出一条什么都没治理的审计记录。
         */
        @Test
        @DisplayName("重复项列表过滤 master 后为空: 400, 不写空审计")
        void refusesWhenOnlyMasterListedAsDuplicate() {
            WorkProcessDTO.GovernanceRequest req = WorkProcessDTO.GovernanceRequest.builder()
                    .masterProcessId(MASTER_ID)
                    .duplicateProcessIds(List.of(MASTER_ID))
                    .mode(WorkProcessDTO.GovernanceMode.MERGE)
                    .idempotencyKey(IDEMPOTENCY_KEY)
                    .build();

            assertThatThrownBy(() -> workProcessService.governDuplicates(FACTORY_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少选择一条");

            verify(governanceAuditRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("审计留痕")
    class AuditTrail {

        @Test
        @DisplayName("成功治理写一条审计, 且记录的是排序去重后的 governedProcessIds")
        void writesAuditRowWithNormalizedGovernedIds() {
            WorkProcess master = process(MASTER_ID, true);
            WorkProcess duplicate = process(DUP_ID, true);
            givenLockedGroup(master, duplicate);

            workProcessService.governDuplicates(FACTORY_ID, request(WorkProcessDTO.GovernanceMode.MERGE));

            ArgumentCaptor<WorkProcessGovernanceAudit> captor =
                    ArgumentCaptor.forClass(WorkProcessGovernanceAudit.class);
            verify(governanceAuditRepository).save(captor.capture());
            WorkProcessGovernanceAudit saved = captor.getValue();

            assertThat(saved.getFactoryId()).isEqualTo(FACTORY_ID);
            assertThat(saved.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
            assertThat(saved.getMasterProcessId()).isEqualTo(MASTER_ID);
            assertThat(saved.getGovernedProcessIds()).isEqualTo(DUP_ID);
            assertThat(saved.getMode()).isEqualTo(WorkProcessDTO.GovernanceMode.MERGE);
            assertThat(saved.getOperator()).isNotBlank();
        }
    }
}
