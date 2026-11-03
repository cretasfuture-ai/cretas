package com.cretas.aims.service.workstation;

import com.cretas.aims.entity.iot.IotDeviceData;
import com.cretas.aims.service.IotDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 缺口21: 秤读数丢单位 / 读不到返回 0。 */
class WorkstationScaleReadingUnitContractTest {

    private final IotDataService iot = mock(IotDataService.class);
    private final WorkstationCountingService service = newService();

    private WorkstationCountingService newService() {
        return new WorkstationCountingService(null, null, iot, null, null, null, new ObjectMapper());
    }

    private void latest(String json) {
        IotDeviceData d = new IotDeviceData();
        d.setDataValue(json);
        when(iot.getRecentData(anyString(), anyString(), anyInt())).thenReturn(List.of(d));
    }

    @Test
    void gramReadingIsConvertedToKg() {
        latest("{\"data\":{\"weight\":1500,\"unit\":\"g\"}}");
        assertThat(service.readScaleWeight("S1", "F006")).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("1.5"));
    }

    @Test
    void kgAndUnitlessReadingsKeepTheirValue() {
        latest("{\"weight\":2.5,\"unit\":\"kg\"}");
        assertThat(service.readScaleWeight("S1", "F006")).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("2.5"));
        latest("{\"weight\":2.5}");
        assertThat(service.readScaleWeight("S1", "F006")).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("2.5"));
    }

    @Test
    void unknownOrNonMassUnitIsNotAReading() {
        latest("{\"data\":{\"weight\":3,\"unit\":\"只\"}}");
        assertThat(service.readScaleWeight("S1", "F006")).isEmpty();
    }

    @Test
    void noDataMalformedOrFailingReadIsEmptyNotZero() {
        when(iot.getRecentData(anyString(), anyString(), anyInt())).thenReturn(List.of());
        assertThat(service.readScaleWeight("S1", "F006")).isEqualTo(Optional.empty());
        latest("not json");
        assertThat(service.readScaleWeight("S1", "F006")).isEmpty();
        when(iot.getRecentData(anyString(), anyString(), anyInt())).thenThrow(new RuntimeException("db down"));
        assertThat(service.readScaleWeight("S1", "F006")).isEmpty();
        assertThat(service.readScaleWeight(null, "F006")).isEmpty();
    }

    @Test
    void scaleReadingToKgHandlesNaN() {
        assertThat(WorkstationCountingService.scaleReadingToKg(Double.NaN, "kg")).isEmpty();
        assertThat(WorkstationCountingService.scaleReadingToKg(500, "g").map(BigDecimal::toPlainString))
                .contains("0.5");
    }
}
