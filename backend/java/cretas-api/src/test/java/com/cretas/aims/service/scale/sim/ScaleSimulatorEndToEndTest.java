package com.cretas.aims.service.scale.sim;

import com.cretas.aims.entity.ProcessingStageRecord;
import com.cretas.aims.entity.iot.IotDeviceData;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.ProcessingStageRecordRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.IotDataService;
import com.cretas.aims.service.impl.ScaleProtocolAdapterServiceImpl;
import com.cretas.aims.service.mqtt.MqttSubscriber;
import com.cretas.aims.service.scale.sim.ScaleGatewayBridge.Framing;
import com.cretas.aims.service.scale.sim.ScaleSimSupport.Fixture;
import com.cretas.aims.service.workstation.WorkstationCountingService;
import com.cretas.aims.service.workstation.WorkstationCountingService.CountingResult;
import com.cretas.aims.service.workstation.WorkstationCountingService.WorkstationConfig;
import com.cretas.aims.websocket.EquipmentMonitoringHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 端到端 (不含真秤、不含 Spring/MQTT broker/数据库):
 * 模拟器的帧 → TCP 流 → 网关(测试里扮演) → 线上解析入口 → MqttSubscriber(线上) → iot 数据 →
 * WorkstationCountingService.manualCount(线上) → 报工载荷里的重量。
 *
 * <p>固定样本来自 tools/scale-simulator/fixtures/frames.json (模拟器生成, 单一事实来源)。
 */
class ScaleSimulatorEndToEndTest {

    private static final String FACTORY = "F006";
    private static final String DEVICE = "SCALE-SIM-1";
    private static final String WS = "WS-SIM-1";

    private final ScaleProtocolAdapterServiceImpl adapter = ScaleSimSupport.simAdapter();
    private final LinkedList<IotDeviceData> store = new LinkedList<>();   // 最新在前
    private final List<BigDecimal> lastWeightUpdates = new ArrayList<>();
    private IotDataService iot;
    private MqttSubscriber mqtt;
    private WorkstationCountingService counting;

    @BeforeEach
    void wire() {
        iot = mock(IotDataService.class);
        doSave();
        when(iot.getRecentData(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            int limit = inv.getArgument(2);
            return new ArrayList<>(store.subList(0, Math.min(limit, store.size())));
        });
        org.mockito.Mockito.doAnswer(inv -> {
            lastWeightUpdates.add(inv.getArgument(1));
            return null;
        }).when(iot).updateEquipmentLastWeight(anyString(), any(BigDecimal.class), any(LocalDateTime.class));

        mqtt = new MqttSubscriber(ScaleSimSupport.MAPPER, mock(EquipmentMonitoringHandler.class), iot);

        ProcessingStageRecordRepository stageRepo = mock(ProcessingStageRecordRepository.class);
        when(stageRepo.save(any(ProcessingStageRecord.class))).thenAnswer(inv -> {
            ProcessingStageRecord r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        counting = new WorkstationCountingService(null, null, iot, stageRepo,
                mock(BatchWorkSessionRepository.class), mock(ProductionBatchRepository.class),
                ScaleSimSupport.MAPPER);
        counting.initWorkstation(WorkstationConfig.builder()
                .workstationId(WS).factoryId(FACTORY).scaleDeviceId(DEVICE).build());
    }

    private void doSave() {
        org.mockito.Mockito.doAnswer(inv -> {
            JsonNode data = inv.getArgument(3);
            IotDeviceData d = new IotDeviceData();
            d.setDeviceId(inv.getArgument(1));
            d.setFactoryId(inv.getArgument(0));
            d.setDataType(inv.getArgument(2));
            d.setDataValue(data.toString());
            d.setReceivedAt(LocalDateTime.now());
            store.addFirst(d);
            return null;
        }).when(iot).saveDeviceData(anyString(), anyString(), anyString(), any(JsonNode.class));
    }

    private static byte[] frame(String protocol, String scenario) {
        return ScaleSimSupport.fixtures().stream()
                .filter(f -> f.protocol().equals(protocol) && f.scenario().equals(scenario))
                .map(Fixture::bytes).findFirst().orElseThrow();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private ScaleGatewayBridge keliBridge() {
        return new ScaleGatewayBridge(adapter, mqtt, "KELI_D2008_ASCII", Framing.CRLF_LINES, FACTORY, DEVICE);
    }

    /** 起一个一次性 TCP 服务, 每个连接依次发一份字节后断开 (= 模拟器 serve --drop-after)。 */
    private ServerSocket serve(byte[]... perConnection) throws IOException {
        ServerSocket server = new ServerSocket(0);
        server.setSoTimeout(5000);
        CompletableFuture.runAsync(() -> {
            try {
                for (byte[] payload : perConnection) {
                    try (Socket c = server.accept()) {
                        c.getOutputStream().write(payload);
                        c.getOutputStream().flush();
                    }
                }
            } catch (IOException ignored) {
                // 测试结束关 server 时会走到这里
            }
        });
        return server;
    }

    // ------------------------------------------------------------------ 好帧一路到报工

    @Test
    void stableFrameOverTcpEndsUpAsTheWeightInTheCountingPayload() throws Exception {
        byte[] stream = cat(frame("KELI_D2008_ASCII", "unstable"), frame("KELI_D2008_ASCII", "stable"));
        try (ServerSocket server = serve(stream)) {
            ScaleGatewayBridge bridge = keliBridge();
            bridge.connectAndDrain(server.getLocalPort());

            assertThat(bridge.accepted).hasSize(2);
            assertThat(bridge.rejected).isEmpty();
        }
        CountingResult r = counting.manualCount(FACTORY, WS, null);

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getProductWeight()).isEqualByComparingTo("124.0");   // 模拟器发的稳定 +001240 kg S
        assertThat(r.getTotalWeight()).isEqualByComparingTo("124.0");
        assertThat(lastWeightUpdates).isNotEmpty();
    }

    @Test
    void gramsFrameIsConvertedToKgInThePayload() {
        keliBridge().feed(frame("KELI_D2008_ASCII", "grams_unit"));   // 1500.0 g
        CountingResult r = counting.manualCount(FACTORY, WS, null);
        assertThat(r.getProductWeight()).isEqualByComparingTo("1.5");
    }

    @Test
    void modbusFrameGoesThroughTheSameChain() {
        ScaleGatewayBridge b = new ScaleGatewayBridge(adapter, mqtt, "MODBUS_RTU_STANDARD",
                Framing.MODBUS_RTU, FACTORY, DEVICE);
        b.feed(frame("MODBUS_RTU_STANDARD", "normal"));
        assertThat(counting.manualCount(FACTORY, WS, null).getProductWeight()).isEqualByComparingTo("12.34");
    }

    @Test
    void negativeReadingAfterTareKeepsItsSign() {
        keliBridge().feed(frame("KELI_D2008_ASCII", "negative_after_tare"));
        assertThat(counting.manualCount(FACTORY, WS, null).getProductWeight()).isEqualByComparingTo("-52.0");
    }

    @Test
    void trueZeroIsAReadingNotAMissingReading() {
        keliBridge().feed(frame("KELI_D2008_ASCII", "zero"));
        CountingResult r = counting.manualCount(FACTORY, WS, null);
        assertThat(r.getProductWeight()).isNotNull().isEqualByComparingTo("0");
        assertThat(r.getMessage()).doesNotContain("没读到");
    }

    // ------------------------------------------------------------------ 坏帧 / 不稳定: 不进报工, 更不是 0

    @Test
    void unstableReadingIsNotUsedInTheCountingPayload() {
        keliBridge().feed(frame("KELI_D2008_ASCII", "unstable"));
        CountingResult r = counting.manualCount(FACTORY, WS, null);

        assertThat(r.isSuccess()).isTrue();              // 计数本身照常
        assertThat(r.getProductWeight()).isNull();       // 但没有可用重量, ⛔ 不是 0
        assertThat(r.getTotalWeight()).isEqualByComparingTo("0");
        assertThat(r.getMessage()).contains("没读到秤重量");
    }

    @Test
    void everyCorruptFrameIsDroppedAtTheGatewayAndNeverBecomesZeroKg() {
        List<Fixture> bad = ScaleSimSupport.fixtures().stream()
                .filter(f -> f.protocol().equals("KELI_D2008_ASCII") && !f.ok()).toList();
        assertThat(bad).hasSizeGreaterThanOrEqualTo(8);
        ScaleGatewayBridge bridge = keliBridge();
        for (Fixture f : bad) {
            bridge.feed(f.bytes());
        }
        // 每个坏场景恰好丢一帧 (无 CRLF 的几个也不会被拼到下一帧), 一帧都没发进 MQTT
        assertThat(bridge.accepted).isEmpty();
        assertThat(bridge.rejected).hasSize(bad.size());
        assertThat(store).isEmpty();
        verify(iot, never()).saveDeviceData(anyString(), anyString(), anyString(), any(JsonNode.class));
        verify(iot, never()).updateEquipmentLastWeight(anyString(), any(BigDecimal.class), any(LocalDateTime.class));
        // 对照: 同一条链, 好帧一定会进来 (证明上面的「没发生」不是因为链根本没接上)
        bridge.feed(frame("KELI_D2008_ASCII", "stable"));
        assertThat(store).hasSize(1);
        assertThat(counting.manualCount(FACTORY, WS, null).getProductWeight()).isEqualByComparingTo("124.0");
    }

    @Test
    void halfFrameGluedToTheNextFrameIsRejectedAndTheStreamResyncs() {
        byte[] stream = cat(frame("KELI_D2008_ASCII", "truncated"),            // +0012 (没有 CRLF)
                frame("KELI_D2008_ASCII", "stable"),                          // 紧跟一整帧: 粘成一行
                frame("KELI_D2008_ASCII", "stable"));
        ScaleGatewayBridge bridge = keliBridge();
        bridge.feed(stream);
        assertThat(bridge.rejected).hasSize(1);   // 粘连行
        assertThat(bridge.accepted).hasSize(1);   // 下一整帧恢复同步
    }

    @Test
    void corruptModbusFramesAreRejectedAtTheGateway() {
        ScaleGatewayBridge b = new ScaleGatewayBridge(adapter, mqtt, "MODBUS_RTU_STANDARD",
                Framing.MODBUS_RTU, FACTORY, DEVICE);
        b.feed(frame("MODBUS_RTU_STANDARD", "bad_crc"));
        b.feed(frame("MODBUS_RTU_STANDARD", "truncated"));
        b.feed(frame("MODBUS_RTU_STANDARD", "exception_response"));
        assertThat(b.accepted).isEmpty();
        assertThat(b.rejected).hasSize(3);
        assertThat(store).isEmpty();
    }

    // ------------------------------------------------------------------ 断线重连

    @Test
    void disconnectThenReconnectResumesReadingsOnANewConnection() throws Exception {
        byte[] conn1 = frame("KELI_D2008_ASCII", "stable");                                   // 124.0 然后断线
        byte[] conn2 = cat(frame("KELI_D2008_ASCII", "truncated"),                            // 重连后先来一个残帧
                new byte[]{0x0D, 0x0A},                                                       // 残帧被换行截断 → 单独成行被拒
                keliFrameKg(88.8));
        try (ServerSocket server = serve(conn1, conn2)) {
            ScaleGatewayBridge bridge = keliBridge();
            bridge.connectAndDrain(server.getLocalPort());
            assertThat(counting.manualCount(FACTORY, WS, null).getProductWeight()).isEqualByComparingTo("124.0");

            bridge.connectAndDrain(server.getLocalPort());   // 重连
            assertThat(bridge.connections).isEqualTo(2);
            assertThat(counting.manualCount(FACTORY, WS, null).getProductWeight()).isEqualByComparingTo("88.8");
            assertThat(bridge.rejected).hasSize(1);
        }
    }

    /**
     * 已知缺口 (钉住现状, 不是期望行为): 秤断线后, 最近一条旧读数仍会被报工当成当前重量,
     * 因为 readScaleWeight 不看读数多久以前收到的。修法需要业务定「多久算过期」(秤若只在重量变化时才发消息,
     * 过期阈值会误伤正常称重), 所以没有在这个 PR 里加。详见现状文档 §5。
     */
    @Test
    void knownGapStaleReadingAfterDisconnectIsStillUsed() throws Exception {
        try (ServerSocket server = serve(frame("KELI_D2008_ASCII", "stable"))) {
            keliBridge().connectAndDrain(server.getLocalPort());
        }
        store.getFirst().setReceivedAt(LocalDateTime.now().minusHours(3));   // 3 小时前的读数, 秤早已断线
        assertThat(counting.manualCount(FACTORY, WS, null).getProductWeight())
                .as("缺口: 3 小时前的读数仍被采用; 修好后改成期望 null 并更新现状文档")
                .isEqualByComparingTo("124.0");
    }

    // ------------------------------------------------------------------ MQTT 订阅层自身

    @Test
    void mqttWeightMessageWithoutWeightIsNotZeroKg() {
        String payload = "{\"type\":\"WEIGHT\",\"data\":{\"unit\":\"kg\",\"stable\":true}}";
        mqtt.handleMessage(org.springframework.messaging.support.MessageBuilder.withPayload(payload)
                .setHeader("mqtt_receivedTopic", "cretas/" + FACTORY + "/device/" + DEVICE + "/data").build());

        verify(iot, never()).updateEquipmentLastWeight(anyString(), any(BigDecimal.class), any(LocalDateTime.class));
        assertThat(counting.manualCount(FACTORY, WS, null).getProductWeight()).isNull();
        // 对照: 带重量的同类消息一定会更新
        mqtt.handleMessage(org.springframework.messaging.support.MessageBuilder.withPayload(
                        "{\"type\":\"WEIGHT\",\"data\":{\"weight\":5,\"unit\":\"kg\",\"stable\":true}}")
                .setHeader("mqtt_receivedTopic", "cretas/" + FACTORY + "/device/" + DEVICE + "/data").build());
        verify(iot).updateEquipmentLastWeight(eq(DEVICE), any(BigDecimal.class), any(LocalDateTime.class));
    }

    private static byte[] keliFrameKg(double kg) {
        String s = String.format("+%06d kg S\r\n", Math.round(kg * 10));
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
