package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.event.MaterialBatchReleasedEvent;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 2026-10-29 验收 D-S3S4-02: 仓库页「批次状态」把质检中改成可用, 也是一次放行 —— 必须发
 * {@link MaterialBatchReleasedEvent}, 否则缺料实例不刷新, 随后按旧缺口 (10 而不是 7) 生成采购草稿。
 * 另钉住: 本事件只经 {@link MaterialBatchReleasedEvent#publishIfBecameAvailable} 发, 别处不许自己 new,
 * 免得下一条放行路径又漏发。
 */
class WarehouseBatchStatusReleasePublishesShortageEventContractTest {

    private final MaterialBatchRepository batches = mock(MaterialBatchRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(
            batches, null, null, mock(MaterialBatchMapper.class), null, null, null, null);

    private MaterialBatch batch(MaterialBatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        b.setId("MB-1");
        b.setFactoryId("F1");
        b.setMaterialTypeId("M1");
        b.setStatus(status);
        when(batches.findById("MB-1")).thenReturn(Optional.of(b));
        when(batches.save(any())).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(service, "applicationEventPublisher", publisher);
        // 纪律排查 A3: 默认只能质检放行; 这里测的是「放行后要发事件」, 所以配成允许手动放行
        ReflectionTestUtils.setField(service, "materialBatchReleaseGuard",
                new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard(
                        com.cretas.aims.service.discipline.DisciplineFixtures.policy(java.util.Map.of(
                                com.cretas.aims.service.canvas.ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "QC_OR_MANUAL")),
                        mock(com.cretas.aims.repository.material.MaterialBatchReleaseRepository.class)));
        ReflectionTestUtils.setField(service, "qcReleaseRecordRepository",
                mock(com.cretas.aims.repository.quality.QcReleaseRecordRepository.class));   // 没有质检放行记录
        ReflectionTestUtils.setField(service, "processTimeLimitEventRepository",
                org.mockito.Mockito.mock(com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository.class));
        return b;
    }

    @Test
    void inspectingToAvailableFromTheWarehousePagePublishesTheRelease() {
        batch(MaterialBatchStatus.INSPECTING);

        service.updateBatchStatus("F1", "MB-1", MaterialBatchStatus.AVAILABLE, new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange(
                com.cretas.aims.entity.material.MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE, "已线下复检", 7L, "warehouse_manager"));

        ArgumentCaptor<ApplicationEvent> ev = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOfSatisfying(MaterialBatchReleasedEvent.class, e -> {
            assertThat(e.getFactoryId()).isEqualTo("F1");
            assertThat(e.getMaterialTypeId()).isEqualTo("M1");
            assertThat(e.getMaterialBatchId()).isEqualTo("MB-1");
        });
    }

    /** 阴性对照: 本来就可用的再改成可用, 两种事件都不发; 不是从质检中出来的改状态也不发。 */
    @Test
    void anAlreadyAvailableBatchOrANonInspectingChangePublishesNothing() {
        batch(MaterialBatchStatus.AVAILABLE);
        service.updateBatchStatus("F1", "MB-1", MaterialBatchStatus.AVAILABLE, new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange(
                com.cretas.aims.entity.material.MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE, "已线下复检", 7L, "warehouse_manager"));

        batch(MaterialBatchStatus.AVAILABLE);
        service.updateBatchStatus("F1", "MB-1", MaterialBatchStatus.FROZEN, new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange(
                com.cretas.aims.entity.material.MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE, "已线下复检", 7L, "warehouse_manager"));

        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    /**
     * 原名 anAlreadyAvailableBatchOrAnotherTargetPublishesNothing, 其中「质检中改冻品什么都不发」守的是复审 R1 要去掉的行为:
     * 离开质检中 (不合格 / 冻品 / 鲜品 / 库存中) 时「质检中数量」变了, 缺料实例必须重算。
     * 缺料分析的可用量只数 AVAILABLE, 所以这些都<b>不是</b>放行, 发的是离开质检中事件。
     */
    @Test
    void leavingInspectionForAnyNonAvailableStatusPublishesInspectionClosedNotARelease() {
        for (MaterialBatchStatus target : List.of(MaterialBatchStatus.DEFECTIVE, MaterialBatchStatus.FROZEN,
                MaterialBatchStatus.FRESH, MaterialBatchStatus.IN_STOCK)) {
            org.mockito.Mockito.reset(publisher);
            batch(MaterialBatchStatus.INSPECTING);
            service.updateBatchStatus("F1", "MB-1", target, new com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.ManualChange(
                    com.cretas.aims.entity.material.MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE, "已线下复检", 7L, "warehouse_manager"));

            ArgumentCaptor<ApplicationEvent> ev = ArgumentCaptor.forClass(ApplicationEvent.class);
            verify(publisher).publishEvent(ev.capture());
            verify(publisher, never()).publishEvent(any(Object.class));
            assertThat(ev.getValue()).as("%s", target).isInstanceOfSatisfying(
                    com.cretas.aims.event.MaterialBatchInspectionClosedEvent.class, e -> {
                        assertThat(e.getMaterialTypeId()).isEqualTo("M1");
                        assertThat(e.getMaterialBatchId()).isEqualTo("MB-1");
                        assertThat(e.getNewStatus()).isEqualTo(target);
                    });
        }
    }

    /**
     * 复审 R1: 「改状态之后给缺料实例发什么」只有一份 —— {@code QualityDispositionInventoryService.publishMaterialStatusChange}。
     * 两个事件的发布方法除了它和事件类自己, 业务代码里谁都不许直接调 (手写的那几份只发了放行、漏了判不合格)。
     */
    @Test
    void theStatusChangeEventsArePublishedOnlyThroughTheSharedFunction() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !List.of("MaterialBatchReleasedEvent.java", "MaterialBatchInspectionClosedEvent.java",
                            "QualityDispositionInventoryService.java").contains(p.getFileName().toString()))
                    .filter(p -> DIRECT_PUBLISH.matcher(read(p)).find())
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).as("这些地方直接调了事件发布方法, 应改走 publishMaterialStatusChange / applyMaterialFlip").isEmpty();
        // 阳性对照: 扫描确实认得出调用, 且共用函数本身确实在调
        assertThat(DIRECT_PUBLISH.matcher("MaterialBatchReleasedEvent.publishIfBecameAvailable (p, this, b, s)").find()).isTrue();
        assertThat(DIRECT_PUBLISH.matcher(read(root.resolve(
                "com/cretas/aims/service/quality/QualityDispositionInventoryService.java"))).results().count()).isEqualTo(2);
    }

    /** 两个发布方法的调用 (方法名 + 可选空白 + 左括号); javadoc 里的 {@code {@link #x}} 没有括号, 不算。 */
    private static final Pattern DIRECT_PUBLISH = Pattern.compile(
            "\\b(?:publishIfBecameAvailable|publishIfLeftInspectingUnreleased)\\s*\\(");

    @Test
    void theScanSeesSpacedAndQualifiedConstructions() {
        assertThat(CONSTRUCTION.matcher("x = new MaterialBatchReleasedEvent (this, f, m, b);").find()).isTrue();
        assertThat(CONSTRUCTION.matcher("new com.cretas.aims.event.MaterialBatchReleasedEvent\n        (this, f, m, b)").find()).isTrue();
        assertThat(CONSTRUCTION.matcher("MaterialBatchReleasedEvent.publishIfBecameAvailable(p, this, b, s)").find()).isFalse();
    }

    @Test
    void theReleaseEventIsOnlyConstructedThroughTheSharedMethod() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("MaterialBatchReleasedEvent.java"))
                    .filter(p -> CONSTRUCTION.matcher(read(p)).find())
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).as("这些地方自己 new 了放行事件, 应改走 publishIfBecameAvailable").isEmpty();
    }

    /** {@code new [com.cretas.aims.event.]MaterialBatchReleasedEvent (} —— 关键字、包名与括号之间的空白 / 换行都算。 */
    private static final Pattern CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+(?:com\\s*\\.\\s*cretas\\s*\\.\\s*aims\\s*\\.\\s*event\\s*\\.\\s*)?MaterialBatchReleasedEvent\\s*\\(");

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
