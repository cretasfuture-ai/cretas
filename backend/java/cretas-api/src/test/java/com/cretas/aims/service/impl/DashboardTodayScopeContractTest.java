package com.cretas.aims.service.impl;

import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 首页四张 KPI 卡：<b>说「今日」就得真是今日，而且得真的发出来</b>。
 *
 * <h2>🔴 两个各自独立的缺陷（2026-08-19 F006 prod 实测）</h2>
 *
 * <p>客户截图上「今日产量 0 kg」「完成批次 0 个」，而同一时刻库里：
 *
 * <pre>
 * 批次 10766  创建 08-18  完成 08-19  良品 75.00
 * 批次 10779  创建 08-19  完成 08-19  良品 40.00
 *   ⇒ 今天完成 2 个批次 / 115.00
 * </pre>
 *
 * <h3>缺陷一：投影丢失 —— 这两张卡<b>恒为 0</b>，与数据无关</h3>
 *
 * <p>{@code getDashboardOverview} 把 {@code completedBatches} 放进 {@code summary}、
 * 把 {@code todayOutputKg} 放进 {@code todayStats}，而前端读的是<b>顶层</b>的
 * {@code overview.todayOutput} / {@code overview.completedBatches}
 * （{@code DashboardOverview} 类型也是这么声明的）。取不到 → {@code ?? 0} → 恒 0。
 *
 * <p>⚠️ 同一屏第四张卡「设备告警」<b>得过一模一样的病并且修过</b>——
 * 那里留着一句注释：「{@code dashboard/overview returns it nested at summary.activeAlerts}」，
 * 修法是在前端加 fallback 链。<b>但同屏另外两张卡没跟着改。</b>
 * ⇒ 「改共享结构前先数，改完再数」：那次该数的是「这一屏有几张卡读顶层」，不是「我修的这张」。
 *
 * <h3>缺陷二：口径 —— 「完成批次」量的是「今天<b>创建</b>的里有多少已完成」</h3>
 *
 * <p>原实现 {@code countByFactoryIdAndStatusAndCreatedAtAfter(COMPLETED, 今天0点)}。
 * 批次 10766 是<b>昨天创建、今天完成</b>的 75 kg —— 卡片语义算它，这个查询不算它。
 * 「今日产量」同源：{@code calculateTotalOutputAfter} 也按 {@code createdAt} 筛。
 *
 * <p>形态 A：<b>我想知道的 X（今天完成了几批）≠ 我实际在量的 Y（今天建的批次里完成了几个）</b>。
 *
 * <p>⚠️ 这两个缺陷要分开验：只修口径，卡还是 0（投影仍然丢）；
 * 只修投影，数字会变成 40 而不是 115（口径仍然错）。<b>两条断言缺一不可。</b>
 */
@DisplayName("首页 KPI：今日口径 + 顶层投影")
class DashboardTodayScopeContractTest {

    private static final String F = "F006";

    private ProcessingServiceImpl service;
    private ProductionBatchRepository batchRepo;
    private MaterialBatchRepository materialRepo;
    private QualityInspectionRepository qualityRepo;
    private EquipmentRepository equipmentRepo;
    private EquipmentAlertRepository alertRepo;
    private UserRepository userRepo;

    /** 今天完成的（含昨天创建的那一张）——卡片语义要的就是这个。 */
    private static final long COMPLETED_TODAY = 2L;
    private static final BigDecimal OUTPUT_TODAY = new BigDecimal("115.00");

    /** 今天创建的里已完成的——旧实现量的是这个，少了 10766 那张。 */
    private static final long COMPLETED_AMONG_CREATED_TODAY = 1L;
    private static final BigDecimal OUTPUT_AMONG_CREATED_TODAY = new BigDecimal("40.00");

    @BeforeEach
    void setUp() {
        service = mock(ProcessingServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        batchRepo = mock(ProductionBatchRepository.class);
        materialRepo = mock(MaterialBatchRepository.class);
        qualityRepo = mock(QualityInspectionRepository.class);
        equipmentRepo = mock(EquipmentRepository.class);
        alertRepo = mock(EquipmentAlertRepository.class);
        userRepo = mock(UserRepository.class);

        ReflectionTestUtils.setField(service, "productionBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialRepo);
        ReflectionTestUtils.setField(service, "qualityInspectionRepository", qualityRepo);
        ReflectionTestUtils.setField(service, "equipmentRepository", equipmentRepo);
        ReflectionTestUtils.setField(service, "equipmentAlertRepository", alertRepo);
        ReflectionTestUtils.setField(service, "userRepository", userRepo);

        // ── 两个口径分别给不同的数, 这样「用错了哪个」一眼可辨 ──
        when(batchRepo.countByFactoryIdAndStatusAndEndTimeAfter(
                anyString(), eq(ProductionBatchStatus.COMPLETED), any(LocalDateTime.class)))
                .thenReturn(COMPLETED_TODAY);
        when(batchRepo.countByFactoryIdAndStatusAndCreatedAtAfter(
                anyString(), eq(ProductionBatchStatus.COMPLETED), any(LocalDateTime.class)))
                .thenReturn(COMPLETED_AMONG_CREATED_TODAY);
        when(batchRepo.calculateTotalOutputCompletedAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn(OUTPUT_TODAY);
        when(batchRepo.calculateTotalOutputAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn(OUTPUT_AMONG_CREATED_TODAY);

        // 其余查询给中性值 —— 它们不是本测试的对象, 但不桩会 NPE
        when(alertRepo.findByFactoryIdAndStatusOrderByTriggeredAtDesc(anyString(), any()))
                .thenReturn(List.of());
        when(batchRepo.findCompletedBatchesWithTimes(anyString(), any())).thenReturn(List.of());
    }

    private Map<String, Object> overview() {
        return service.getDashboardOverview(F);
    }

    // ───────────────── 缺陷一：顶层投影 ─────────────────

    @Test
    @DisplayName("「完成批次」必须出现在**顶层** —— 前端读的是 overview.completedBatches")
    void completedBatchesIsProjectedToTopLevel() {
        assertThat(overview())
                .as("""
                        顶层没有这个键 ⇒ 前端 `overview.value?.completedBatches ?? 0` 恒为 0,
                        这张卡与库里有多少数据无关。同屏「设备告警」得过一样的病并修过。""")
                .containsKey("completedBatches");
    }

    @Test
    @DisplayName("「今日产量」必须出现在**顶层** —— 前端读的是 overview.todayOutput")
    void todayOutputIsProjectedToTopLevel() {
        assertThat(overview())
                .as("顶层没有 todayOutput ⇒ 「今日产量」恒为 0 kg")
                .containsKey("todayOutput");
    }

    // ───────────────── 缺陷二：今日口径 ─────────────────

    @Test
    @DisplayName("「完成批次」按**完成时间**算, ⛔ 不是按创建时间")
    void completedBatchesCountsWhatFinishedToday() {
        assertThat(overview().get("completedBatches"))
                .as("""
                        期望 2(今天完成的), 拿到 1 说明量的是「今天创建的里已完成的」——
                        昨天建、今天完成的那张(10766, 75kg)被漏掉了。""")
                .isEqualTo(COMPLETED_TODAY);
    }

    @Test
    @DisplayName("「今日产量」按**完成时间**算, ⛔ 不是按创建时间")
    void todayOutputSumsWhatFinishedToday() {
        assertThat((BigDecimal) overview().get("todayOutput"))
                .as("期望 115.00(今天完成的合计), 拿到 40.00 说明漏了昨天建今天完成的那 75.00")
                .isEqualByComparingTo(OUTPUT_TODAY);
    }

    // ───────────────── 不许悄悄改坏别人 ─────────────────

    @Test
    @DisplayName("既有的嵌套键保持存在 —— 顶层是**新增**投影, 不是搬家")
    void nestedKeysStillThere() {
        Map<String, Object> o = overview();
        assertThat(o).as("summary/todayStats 仍有消费方, 不许搬走")
                .containsKeys("summary", "todayStats", "alerts");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) o.get("summary");
        assertThat(summary)
                .as("summary.activeAlerts 是第四张卡的 fallback 链在读的, 动它会把已修好的卡弄坏")
                .containsKey("activeAlerts");
    }
}
