package com.cretas.aims.service.quality.firstarticle;

import com.cretas.aims.dto.quality.FirstArticleDtos.InspectionView;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgePreview;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgeRequest;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection.Verdict;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleInspectionRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticlePolicyRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleStandardRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.Item;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.ItemType;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.ResultInput;
import com.cretas.aims.service.workflow.FactorySelfApprovalGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首检判定的四眼: 送检人判自己送的样, 看工厂「允许本人审批」开关 ({@link FactorySelfApprovalGate}, 真对象)。
 * <ul>
 *   <li>开关关 (没有设置行 / 显式 false): 403 FIRST_ARTICLE_SELF_JUDGE_FORBIDDEN, 写明原因, 单不落库; 预览 canSubmit=false;</li>
 *   <li>开关开: 必须写原因 (没写 422), 写了 → 判定成功并留痕 selfJudged + 原因;</li>
 *   <li>阳性对照: 别人判定不受开关影响。</li>
 * </ul>
 */
class FirstArticleSelfJudgeContractTest {

    private static final String F = "F006";
    private static final long MAKER = 7L;
    private static final long OTHER_INSPECTOR = 8L;
    private final ObjectMapper json = new ObjectMapper();
    private FirstArticleInspectionRepository inspections;
    private FactorySettingsRepository settings;
    private FirstArticleService service;

    @BeforeEach
    void setUp() {
        inspections = mock(FirstArticleInspectionRepository.class);
        settings = mock(FactorySettingsRepository.class);
        WorkProcessRepository processes = mock(WorkProcessRepository.class);
        when(processes.findByFactoryIdAndIdIn(any(), any())).thenReturn(List.of());
        service = new FirstArticleService(mock(FirstArticlePolicyRepository.class), mock(FirstArticleStandardRepository.class),
                inspections, mock(WorkProcessTaskRepository.class), processes, mock(ProductionBatchRepository.class),
                json, new FactorySelfApprovalGate(settings));
        when(inspections.save(any(FirstArticleInspection.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private FirstArticleInspection pending() {
        FirstArticleInspection i = new FirstArticleInspection();
        i.setId(42L);
        i.setFactoryId(F);
        i.setWorkProcessTaskId(31L);
        i.setProductionBatchId(9L);
        i.setWorkProcessId("wp-1");
        i.setStandardId(5L);
        i.setStandardRevision(1);
        i.setStandardName("封口首检");
        i.setStandardItemsJson(FirstArticleItemRules.write(json, List.of(new Item("w", "块重", ItemType.NUMERIC, true, "g",
                new BigDecimal("180"), new BigDecimal("220"), null, null, null))));
        i.setStatus(FirstArticleInspection.Status.PENDING);
        i.setRequestedBy(MAKER);
        i.setRequestedAt(LocalDateTime.now());
        when(inspections.lockByFactoryIdAndId(F, 42L)).thenReturn(Optional.of(i));
        when(inspections.findByFactoryIdAndId(F, 42L)).thenReturn(Optional.of(i));
        return i;
    }

    private static JudgeRequest pass(String selfReason) {
        return new JudgeRequest(List.of(new ResultInput("w", new BigDecimal("200"), null)), Verdict.PASSED, null, selfReason);
    }

    @Test
    @DisplayName("开关关 (没有设置行): 送检人判自己的样 → 403 + 原因, 单不落库; 预览不给提交")
    void makerCannotJudgeWhenSwitchOff() {
        FirstArticleInspection i = pending();
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(null);
        assertThatThrownBy(() -> service.judge(F, 42L, pass("就我一个人"), MAKER))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException b = (BusinessException) e;
                    assertThat(b.getCode()).isEqualTo(403);
                    assertThat(b.getErrorCode()).isEqualTo(FirstArticleService.SELF_JUDGE_FORBIDDEN);
                    assertThat(b.getMessage()).contains("你送检的").contains("另一位");
                });
        verify(inspections, never()).save(any());
        assertThat(i.getStatus()).isEqualTo(FirstArticleInspection.Status.PENDING);

        JudgePreview p = service.previewJudge(F, 42L, pass(null), MAKER);
        assertThat(p.canSubmit()).isFalse();
        assertThat(p.selfJudge()).isTrue();
        assertThat(p.message()).contains("另一位");
    }

    @Test
    @DisplayName("开关显式 false 与没有行同一结论")
    void explicitOffSameAsMissing() {
        pending();
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(false);
        assertThatThrownBy(() -> service.judge(F, 42L, pass("x"), MAKER))
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }

    @Test
    @DisplayName("开关开: 不写原因 → 422; 写了原因 → 判定成功, 留痕 selfJudged + 原因")
    void makerCanJudgeWithReasonWhenSwitchOn() {
        pending();
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(true);
        assertThatThrownBy(() -> service.judge(F, 42L, pass("  "), MAKER))
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(422));
        assertThat(service.previewJudge(F, 42L, pass(null), MAKER).canSubmit()).isFalse();
        assertThat(service.previewJudge(F, 42L, pass("本厂只有我一个质检"), MAKER).canSubmit()).isTrue();

        InspectionView v = service.judge(F, 42L, pass("本厂只有我一个质检"), MAKER);
        assertThat(v.status()).isEqualTo(FirstArticleInspection.Status.PASSED);
        assertThat(v.selfJudged()).isTrue();
        assertThat(v.selfJudgeReason()).isEqualTo("本厂只有我一个质检");
    }

    @Test
    @DisplayName("阳性对照: 别人判定, 开关关也照常; 不留本人判定痕迹")
    void otherInspectorUnaffected() {
        pending();
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(null);
        assertThat(service.previewJudge(F, 42L, pass(null), OTHER_INSPECTOR).canSubmit()).isTrue();
        InspectionView v = service.judge(F, 42L, pass(null), OTHER_INSPECTOR);
        assertThat(v.status()).isEqualTo(FirstArticleInspection.Status.PASSED);
        assertThat(v.selfJudged()).isFalse();
        assertThat(v.selfJudgeReason()).isNull();
    }
}
