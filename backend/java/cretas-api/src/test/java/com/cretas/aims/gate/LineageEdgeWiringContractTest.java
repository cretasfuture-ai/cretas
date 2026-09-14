package com.cretas.aims.gate;

import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 血缘边写点<b>接上没有</b>的闸。
 *
 * <h2>为什么需要它（形态 B：机制在、没接上）</h2>
 *
 * <p>{@code LineageEdgeRecorder} 自己的单测再绿，也只证明「这个函数写得对」，
 * 不证明「生产上有人调它」。本仓的既有前科就是这个形状：
 * {@code WipInventoryServiceImpl.recordWipLineageEdge} 是全仓<b>唯一</b>一处写边，
 * 结果 prod 上 67 条边全是同一种类型、其中 48 条还是自环 —— 引擎在，链没有。
 *
 * <p>所以这里逐个文件确认：每个该写边的服务里，确实有一行调到了 recorder。
 * 删掉任何一处调用，本闸变红。
 *
 * <h2>它用的是代理判据，说清楚它看不见什么（形态 E）</h2>
 *
 * <p>真正想守的是「运行时那条路径真的写了一条边」。静态扫描判不出这个，
 * 所以用代理：<em>文件里出现指定的 recorder 方法名</em>。两个已知盲区：
 * <ol>
 *   <li>判不出这行调用是不是在<b>死代码</b>里。缓解：recorder 单测 + 服务层单测覆盖行为本身。</li>
 *   <li>判不出注入有没有成功。缓解：下面单独断言 {@link LineageEdgeRecorder} 带
 *       {@code @Service} —— 这 7 处用的是 {@code @Autowired(required=false)}，
 *       bean 不存在时会<b>静默注入 null</b>，那正是「机制在、没接上」的完美伪装。</li>
 * </ol>
 *
 * <p>⚠️ 用 {@code @Autowired(required=false)} 而不是构造器参数，是因为这 7 个类的构造器
 * 被 51 个测试文件按位置参数直接 {@code new}，加必需依赖会一次全打红；
 * 代价就是注入失败不会报错 —— 所以那条 {@code @Service} 断言不是装饰。
 */
@DisplayName("血缘边写点必须真的接上（形态 B 闸）")
class LineageEdgeWiringContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 文件 → 该文件里必须出现的 recorder 方法名。 */
    private static final Map<String, String> REQUIRED_CALLS = new LinkedHashMap<>();

    static {
        // ── RAW_TO_PRODUCTION ──
        REQUIRED_CALLS.put(
                "com/cretas/aims/service/processentry/impl/ClerkProcessEntryServiceImpl.java",
                "recordRawToProduction");
        REQUIRED_CALLS.put(
                "com/cretas/aims/service/impl/BatchConsumptionServiceImpl.java",
                "recordRawToProduction");
        REQUIRED_CALLS.put(
                "com/cretas/aims/service/impl/ProcessingServiceImpl.java",
                "recordRawToProduction");
        // ── PRODUCTION_TO_FINISHED ──
        REQUIRED_CALLS.put(
                "com/cretas/aims/service/orchestration/SupplyChainOrchestrator.java",
                "recordProductionToFinished");
        REQUIRED_CALLS.put(
                "com/cretas/aims/service/impl/ProductionPlanServiceImpl.java",
                "recordProductionToFinishedByPlan");
        REQUIRED_CALLS.put(
                "com/cretas/aims/service/yield/impl/InterimSettleServiceImpl.java",
                "recordProductionToFinishedByPlan");
        // ── FINISHED_TO_SHIPMENT ──
        REQUIRED_CALLS.put(
                "com/cretas/aims/service/inventory/impl/SalesServiceImpl.java",
                "recordFinishedToShipment");
    }

    @Test
    @DisplayName("7 个写点各自都有一行 recorder 调用")
    void everyWritePathCallsTheRecorder() throws IOException {
        for (Map.Entry<String, String> e : REQUIRED_CALLS.entrySet()) {
            Path file = MAIN_ROOT.resolve(e.getKey());
            assertThat(Files.exists(file))
                    .as("挂钩文件不存在（被改名/删除了？）: %s", e.getKey())
                    .isTrue();
            String source = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(source)
                    .as("%s 里找不到 lineageEdgeRecorder.%s(...) —— 这一段血缘没有被接上",
                            e.getKey(), e.getValue())
                    .contains("lineageEdgeRecorder." + e.getValue() + "(");
        }
    }

    @Test
    @DisplayName("每个写点都声明了 recorder 字段（否则上一条断言可能只是碰到了注释）")
    void everyWritePathDeclaresTheRecorderField() throws IOException {
        for (String relative : REQUIRED_CALLS.keySet()) {
            String source = Files.readString(MAIN_ROOT.resolve(relative), StandardCharsets.UTF_8);
            assertThat(source)
                    .as("%s 没有声明 LineageEdgeRecorder 字段", relative)
                    .contains("LineageEdgeRecorder lineageEdgeRecorder");
            assertThat(source)
                    .as("%s 的 recorder 字段必须是 @Autowired(required = false)（构造器不能动）", relative)
                    .contains("Autowired(required = false)");
        }
    }

    @Test
    @DisplayName("LineageEdgeRecorder 必须是 @Service —— required=false 注入失败是静默的")
    void recorderIsASpringBean() {
        // 🔴 这不是装饰性断言: 7 处写点用的都是 @Autowired(required = false)。
        //    如果哪天 @Service 被摘掉, Spring 会把 null 注进去, 7 处 null 守卫全部静默跳过,
        //    血缘一条都不写, 而所有测试照样绿 —— 正是「机制在、没接上」。
        assertThat(LineageEdgeRecorder.class.getAnnotation(Service.class))
                .as("LineageEdgeRecorder 掉了 @Service ⇒ 7 处 required=false 注入全变 null, 血缘静默停写")
                .isNotNull();
    }
}
