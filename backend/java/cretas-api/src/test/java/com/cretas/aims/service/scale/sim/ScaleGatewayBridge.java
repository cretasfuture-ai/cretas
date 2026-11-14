package com.cretas.aims.service.scale.sim;

import com.cretas.aims.dto.scale.ScaleDataParseResult;
import com.cretas.aims.service.impl.ScaleProtocolAdapterServiceImpl;
import com.cretas.aims.service.mqtt.MqttSubscriber;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.messaging.support.MessageBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试里扮演「串口/TCP → MQTT 网关」。
 *
 * <p>⚠️ 生产里没有这个组件: 仓库里没有任何代码从串口 / TCP 读秤的原始帧 (见 docs/hardware/scale-integration-current-state.md)。
 * 这里按设计意图补出来, 好让「模拟器 → 解析 → MQTT 订阅 → 报工」这条链能整条跑通;
 * 真正的网关 (谁来读串口、跑在哪、怎么发 MQTT) 是现场联调前必须定的事。
 *
 * <p>行为: 从 TCP 流里切帧 → 走线上解析入口 → 解析成功就按 MqttSubscriber 约定的主题 / JSON 发出去,
 * 解析失败就丢弃并计数 (⛔ 不发 0 公斤)。
 */
final class ScaleGatewayBridge {

    enum Framing { CRLF_LINES, MODBUS_RTU }

    private final ScaleProtocolAdapterServiceImpl adapter;
    private final MqttSubscriber mqtt;
    private final String protocolCode;
    private final Framing framing;
    private final String factoryId;
    private final String deviceId;

    final List<ScaleDataParseResult> accepted = new ArrayList<>();
    final List<ScaleDataParseResult> rejected = new ArrayList<>();
    int connections = 0;

    ScaleGatewayBridge(ScaleProtocolAdapterServiceImpl adapter, MqttSubscriber mqtt, String protocolCode,
                       Framing framing, String factoryId, String deviceId) {
        this.adapter = adapter;
        this.mqtt = mqtt;
        this.protocolCode = protocolCode;
        this.framing = framing;
        this.factoryId = factoryId;
        this.deviceId = deviceId;
    }

    /** 连一次, 读到对端断开 (EOF) 为止; 返回时相当于「断线」, 调用方再调一次就是「重连」。 */
    void connectAndDrain(int port) throws IOException {
        connections++;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(3000);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                all.write(buf, 0, n);
            }
            feed(all.toByteArray());
        }
    }

    /** 喂一段字节流, 切帧后逐帧处理。 */
    void feed(byte[] stream) {
        for (byte[] frame : split(stream)) {
            handle(frame);
        }
    }

    private List<byte[]> split(byte[] stream) {
        List<byte[]> frames = new ArrayList<>();
        if (framing == Framing.CRLF_LINES) {
            int start = 0;
            for (int i = 0; i + 1 < stream.length; i++) {
                if (stream[i] == 0x0D && stream[i + 1] == 0x0A) {
                    frames.add(java.util.Arrays.copyOfRange(stream, start, i + 2));
                    start = i + 2;
                    i++;
                }
            }
            if (start < stream.length) {
                // 流末尾没有 CRLF 的残帧: 交给解析器 (它必须拒绝)
                frames.add(java.util.Arrays.copyOfRange(stream, start, stream.length));
            }
        } else {
            int i = 0;
            while (i < stream.length) {
                int remaining = stream.length - i;
                int len = remaining >= 3 ? 3 + (stream[i + 2] & 0xFF) + 2 : remaining;
                len = Math.min(len, remaining);
                frames.add(java.util.Arrays.copyOfRange(stream, i, i + len));
                i += len;
            }
        }
        return frames;
    }

    private void handle(byte[] frame) {
        ScaleDataParseResult r = adapter.parseScaleDataByCode(protocolCode, frame);
        if (!Boolean.TRUE.equals(r.getSuccess())) {
            rejected.add(r);
            return;
        }
        accepted.add(r);
        ObjectNode msg = ScaleSimSupport.MAPPER.createObjectNode();
        msg.put("type", "WEIGHT");
        ObjectNode data = msg.putObject("data");
        data.put("weight", r.getWeight());
        data.put("unit", r.getUnit());
        data.put("stable", Boolean.TRUE.equals(r.getStable()));
        mqtt.handleMessage(MessageBuilder.withPayload(msg.toString())
                .setHeader("mqtt_receivedTopic", "cretas/" + factoryId + "/device/" + deviceId + "/data")
                .build());
    }
}
