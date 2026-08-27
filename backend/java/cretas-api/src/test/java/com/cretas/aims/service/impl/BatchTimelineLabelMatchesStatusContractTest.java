package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SystemLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 批次时间线的标签必须跟着真实状态走 —— ⛔ 不许拿 String 去 equals 一个枚举。
 *
 * <h2>🔴 2026-08-28 视觉验收抓到的缺陷</h2>
 *
 * <pre>
 *   ended.put("event", "COMPLETED".equals(batch.getStatus()) ? "生产完成" : "生产取消");
 * </pre>
 *
 * <p>而 {@code ProductionBatch.status} 是 {@code @Enumerated(EnumType.STRING)} 的
 * {@link ProductionBatchStatus} <b>枚举</b>，不是 {@code String}。
 * {@code String.equals(Enum)} <b>恒为 false</b> ⇒ <b>每一个批次的时间线最后一条都写「生产取消」</b>，
 * 包括已完成的。</p>
 *
 * <p><b>生产实测</b>：批次 10823（{@code PB-PLAN-1787622818112-B3898275-15036}）
 * 数据库里 {@code status = COMPLETED}、{@code end_time = 2026-08-25 10:08:09}，
 * 而 web-admin 批次详情页的时间线最后一条写着「生产取消 2026-08-25 10:08:09」。</p>
 *
 * <p>⚠️ 它能活到今天是因为 <b>javac 不报警</b>：{@code equals(Object)} 收任何类型。
 * 这一类缺陷静态类型检查看不出来，只能靠<b>行为断言</b>。</p>
 *
 * <h2>为什么用行为断言而不是源码扫描</h2>
 *
 * <p>源码扫描只能守住「不要再写 {@code "COMPLETED".equals(...)}」这个<b>形状</b>，
 * 换个写法（先 {@code toString()} 再比、或换个字面量）就绕过去了。
 * 这里直接驱动 {@code getBatchTimeline}，断言<b>它产出的标签</b>——
 * 无论实现怎么写，标签错了就红。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("批次时间线: 标签跟着状态走")
class BatchTimelineLabelMatchesStatusContractTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;

    @InjectMocks
    private ProcessingServiceImpl service;

    private static final String FACTORY = "F006";

    /**
     * 逐行剥掉注释再扫。
     *
     * <p>🔴 <b>本闸自己踩过这个坑</b>：第一版没剥注释，而下面被守的那个文件里，
     * 修复的注释<b>逐字引用了那两行坏代码</b>作说明 ⇒ 闸当场红，报的是它自己的注释。
     * 本仓规则里记着「一晚四次源码闸把自己写的注释当被测对象」，这是第五次。</p>
     */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                  .lines()
                  .map(line -> line.replaceAll("//.*$", ""))
                  .reduce("", (a, b) -> a + "\n" + b);
    }

    /** 生产上批次 10823 的形状: 已完成 + 有结束时间 + 无关联计划。 */
    private ProductionBatch batchWith(ProductionBatchStatus status) {
        ProductionBatch b = new ProductionBatch();
        b.setId(10823L);
        b.setFactoryId(FACTORY);
        b.setBatchNumber("PB-PLAN-1787622818112-B3898275-15036");
        b.setCreatedAt(LocalDateTime.of(2026, 8, 25, 9, 55, 15));
        b.setStartTime(LocalDateTime.of(2026, 8, 25, 9, 55, 15));
        b.setEndTime(LocalDateTime.of(2026, 8, 25, 10, 8, 9));
        b.setStatus(status);
        return b;
    }

    private String lastEvent(ProductionBatchStatus status) {
        when(productionBatchRepository.findByIdAndFactoryId(10823L, FACTORY))
                .thenReturn(Optional.of(batchWith(status)));
        List<Map<String, Object>> timeline = service.getBatchTimeline(FACTORY, "10823");
        assertThat(timeline).as("时间线不该为空 —— 空的话下面取 last 没有意义").isNotEmpty();
        return String.valueOf(timeline.get(timeline.size() - 1).get("event"));
    }

    @Test
    @DisplayName("COMPLETED → 最后一条是「生产完成」(缺陷时这里是「生产取消」)")
    void completedBatchSaysCompleted() {
        assertThat(lastEvent(ProductionBatchStatus.COMPLETED))
                .as("生产实测: 批次 10823 状态 COMPLETED 而页面写「生产取消」—— String.equals(Enum) 恒 false")
                .isEqualTo("生产完成");
    }

    @Test
    @DisplayName("阴性对照: CANCELLED → 最后一条是「生产取消」(⛔ 别把闸做成「一律说完成」)")
    void cancelledBatchSaysCancelled() {
        assertThat(lastEvent(ProductionBatchStatus.CANCELLED))
                .as("没有这一条, 上面那条可以靠「无脑返回生产完成」通过")
                .isEqualTo("生产取消");
    }

    /**
     * 补一条源码级判据, 守的是<b>另一个文件</b>里同一个缺陷 ——
     * {@code MobileBusinessServiceImpl} 的人效评分里, 两道「双保险」<b>都</b>是
     * {@code String.equals(Enum)} ⇒ {@code completedCount} 恒为 0 ⇒ 效率分恒为 0。
     *
     * <p>⚠️ 这一条是<b>代理判据</b>: 那段逻辑埋在一个很长的评分方法里, 行为测试要装配
     * 十几个协作者才驱动得起来。⇒ 先用源码判据钉住形状, 并<b>写明它挡不住换写法</b>。
     * ⛔ 不扩成全仓扫描: 全后端 {@code "LIT".equals(x.getStatus())} 共 <b>72 处</b>,
     * 而其中绝大多数比的是真 {@code String status} 字段 —— 宽扫会造出一堆误报,
     * 而误报正是让闸被关掉的原因。
     */
    @Test
    @DisplayName("代理判据: 人效评分不许再拿 String 比 ProductionBatch 的枚举状态")
    void mobileEfficiencyScoreDoesNotCompareStringToEnum() throws IOException {
        Path p = Paths.get("src/main/java/com/cretas/aims/service/mobile/impl/MobileBusinessServiceImpl.java");
        String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));

        // 阳性对照: 确认读到的是真源码, 且那段评分逻辑还在(否则下面的 not.contains 是恒真的)
        assertThat(src).as("阳性对照: 文件读到了").hasSizeGreaterThan(5_000);
        assertThat(src).as("阳性对照: 那段效率分逻辑还在, 否则本断言无意义").contains("efficiencyScore");

        assertThat(src)
                .as("String.equals(枚举) 恒 false ⇒ completedCount 恒 0 ⇒ 效率分恒 0")
                .doesNotContain("\"COMPLETED\".equals(batch.getStatus())")
                .doesNotContain("ProductionBatchStatus.COMPLETED.name().equals(batch.getStatus())");
    }
}
