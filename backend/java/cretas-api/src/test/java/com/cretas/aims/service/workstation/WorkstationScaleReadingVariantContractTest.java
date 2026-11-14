package com.cretas.aims.service.workstation;

import com.cretas.aims.entity.iot.IotDeviceData;
import com.cretas.aims.service.IotDataService;
import com.cretas.aims.service.mqtt.MqttSubscriber;
import com.cretas.aims.websocket.EquipmentMonitoringHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
 * 读数消息里「以前合法的写法」加严后仍然能读 (独立终审 #1705):
 * weight 是字符串 "12.50"; 不稳定标志写成 0 / "false" / "U" 等。入库路径上的 MqttSubscriber 和 readScaleWeight 两处都覆盖。
 */
class WorkstationScaleReadingVariantContractTest {

    private final IotDataService iot = mock(IotDataService.class);
    private final WorkstationCountingService service =
            new WorkstationCountingService(null, null, iot, null, null, null, new ObjectMapper());

    private void latest(String json) {
        IotDeviceData d = new IotDeviceData();
        d.setDataValue(json);
        when(iot.getRecentData(anyString(), anyString(), anyInt())).thenReturn(List.of(d));
    }

    private String read(String weightJson, String stableJson) {
        latest("{\"data\":{\"weight\":" + weightJson + ",\"unit\":\"kg\""
                + (stableJson == null ? "" : ",\"stable\":" + stableJson) + "}}");
        return service.readScaleWeight("S1", "F006").map(BigDecimal::toPlainString).orElse("EMPTY");
    }

    // ---------------------------------------------------------------- weight 字符串

    @Test
    void stringWeightsAreReadAsNumbers() {
        assertThat(read("\"12.50\"", null)).isEqualTo("12.5");
        assertThat(read("\" 3 \"", null)).isEqualTo("3");
        assertThat(read("\"-4.2\"", null)).isEqualTo("-4.2");
        assertThat(read("\"12.\"", null)).isEqualTo("12");
        assertThat(read("12.5", null)).isEqualTo("12.5");
    }

    @Test
    void nonNumericWeightsAreRejectedNotZero() {
        for (String w : new String[]{"\"abc\"", "\"\"", "null", "\"NaN\"", "\"1e3\"", "\"12 kg\"", "true", "[]"}) {
            assertThat(read(w, null)).as("weight=%s", w).isEqualTo("EMPTY");
        }
    }

    // ---------------------------------------------------------------- 不稳定标志的多种写法

    @Test
    void explicitlyUnstableSpellingsAreNotUsedInReports() {
        for (String s : new String[]{"false", "0", "\"false\"", "\"FALSE\"", "\"0\"", "\"U\"", "\"u\"", "\"M\"", "\"unstable\""}) {
            assertThat(read("5", s)).as("stable=%s 是不稳定, 不进报工", s).isEqualTo("EMPTY");
        }
    }

    @Test
    void stableOrAbsentFlagsAreUsed() {
        for (String s : new String[]{null, "true", "1", "\"true\"", "\"S\"", "\"1\"", "\"maybe\""}) {
            assertThat(read("5", s)).as("stable=%s 照常采用", s).isEqualTo("5");
        }
    }

    @Test
    void mqttLogAndReadScaleWeightShareTheSameStableJudgement() throws Exception {
        ObjectMapper m = new ObjectMapper();
        for (String s : new String[]{"true", "1", "\"S\"", "\"1\"", "\"true\""}) {
            assertThat(WorkstationCountingService.isStableFlag(m.readTree(s))).as("stable=%s", s).isTrue();
        }
        for (String s : new String[]{"false", "0", "\"U\"", "\"M\"", "\"false\"", "null"}) {
            assertThat(WorkstationCountingService.isStableFlag(m.readTree(s))).as("stable=%s", s).isFalse();
        }
        assertThat(WorkstationCountingService.isStableFlag(null)).isFalse();
        // 与 readScaleWeight 口径一致: 能被判为稳定的写法, 读数能进报工
        assertThat(read("5", "\"S\"")).isEqualTo("5");
        assertThat(read("5", "\"U\"")).isEqualTo("EMPTY");
    }

    // ---------------------------------------------------------------- MqttSubscriber (入库路径)

    private MqttSubscriber mqtt() {
        return new MqttSubscriber(new ObjectMapper(), mock(EquipmentMonitoringHandler.class), iot);
    }

    private void publish(MqttSubscriber m, String dataJson) {
        m.handleMessage(MessageBuilder.withPayload("{\"type\":\"WEIGHT\",\"data\":" + dataJson + "}")
                .setHeader("mqtt_receivedTopic", "cretas/F006/device/D1/data").build());
    }

    @Test
    void mqttStringWeightUpdatesTheLastReading() {
        publish(mqtt(), "{\"weight\":\"12.50\",\"unit\":\"kg\",\"stable\":true}");
        verify(iot).updateEquipmentLastWeight(eq("D1"), eq(new BigDecimal("12.5")), any(LocalDateTime.class));
    }

    @Test
    void mqttNonNumericStringWeightIsRejected() {
        publish(mqtt(), "{\"weight\":\"abc\",\"unit\":\"kg\"}");
        verify(iot, never()).updateEquipmentLastWeight(anyString(), any(BigDecimal.class), any(LocalDateTime.class));
    }
}
