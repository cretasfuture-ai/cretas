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
        return b;
    }

    @Test
    void inspectingToAvailableFromTheWarehousePagePublishesTheRelease() {
        batch(MaterialBatchStatus.INSPECTING);

        service.updateBatchStatus("F1", "MB-1", MaterialBatchStatus.AVAILABLE);

        ArgumentCaptor<ApplicationEvent> ev = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOfSatisfying(MaterialBatchReleasedEvent.class, e -> {
            assertThat(e.getFactoryId()).isEqualTo("F1");
            assertThat(e.getMaterialTypeId()).isEqualTo("M1");
            assertThat(e.getMaterialBatchId()).isEqualTo("MB-1");
        });
    }

    @Test
    void anAlreadyAvailableBatchOrAnotherTargetPublishesNothing() {
        batch(MaterialBatchStatus.AVAILABLE);
        service.updateBatchStatus("F1", "MB-1", MaterialBatchStatus.AVAILABLE);

        batch(MaterialBatchStatus.INSPECTING);
        service.updateBatchStatus("F1", "MB-1", MaterialBatchStatus.FROZEN);

        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
    }

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
