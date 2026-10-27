package com.cretas.aims.repository;

import com.cretas.aims.entity.iot.IotDeviceData;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.entity.isapi.LabelRecognitionRecord;
import com.cretas.aims.repository.isapi.IsapiDeviceRepository;
import com.cretas.aims.repository.isapi.LabelRecognitionRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摄像头族按工厂查的四个查询 (边缘按 MAC / IP 找本厂设备、电子秤读数、标签识别记录) 能起、且只返回本厂的行。
 * 私网 IP / MAC 在两个工厂重复是常态: 各自只查到自己那台。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class CameraDeviceScopedRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired IsapiDeviceRepository isapiDeviceRepository;
    @Autowired IotDeviceDataRepository iotDeviceDataRepository;
    @Autowired LabelRecognitionRecordRepository labelRecognitionRecordRepository;

    private IsapiDevice camera(String factoryId) {
        IsapiDevice d = IsapiDevice.builder()
                .factoryId(factoryId)
                .deviceName("门口-" + factoryId)
                .deviceType(IsapiDevice.DeviceType.IPC)
                .ipAddress("192.168.1.64")
                .macAddress("AA:BB:CC:DD:EE:FF")
                .username("admin")
                .passwordEncrypted("enc")
                .build();
        return entityManager.persist(d);
    }

    private void scaleReading(String factoryId, String deviceId) {
        IotDeviceData data = new IotDeviceData();
        data.setDeviceId(deviceId);
        data.setDeviceCode("SCALE-1");
        data.setFactoryId(factoryId);
        data.setDataType("WEIGHT");
        data.setDataValue("{\"weight\":1.5}");
        data.setCollectedAt(LocalDateTime.of(2026, 10, 27, 9, 0));
        data.setReceivedAt(LocalDateTime.of(2026, 10, 27, 9, 0));
        data.setProcessed(false);
        entityManager.persist(data);
    }

    private void record(String factoryId, Long configId) {
        entityManager.persist(LabelRecognitionRecord.builder()
                .factoryId(factoryId)
                .configId(configId)
                .deviceId("cam")
                .triggerType(LabelRecognitionRecord.TriggerType.MANUAL)
                .status(LabelRecognitionRecord.RecognitionStatus.SUCCESS)
                .recognitionTime(LocalDateTime.of(2026, 10, 27, 9, 0))
                .build());
    }

    @Test
    void scopedQueriesBootAndReturnOnlyTheFactorysRows() {
        IsapiDevice a = camera("F-CAM-A");
        IsapiDevice b = camera("F-CAM-B");
        scaleReading("F-CAM-A", "scale-1");
        scaleReading("F-CAM-B", "scale-1");
        record("F-CAM-A", 5L);
        record("F-CAM-B", 5L);
        entityManager.flush();
        entityManager.clear();

        assertThat(isapiDeviceRepository.findByFactoryIdAndMacAddressIgnoreCase("F-CAM-A", "aa:bb:cc:dd:ee:ff"))
                .extracting(IsapiDevice::getId).containsExactly(a.getId());
        assertThat(isapiDeviceRepository.findByFactoryIdAndIpAddress("F-CAM-B", "192.168.1.64"))
                .extracting(IsapiDevice::getId).containsExactly(b.getId());
        assertThat(isapiDeviceRepository.findByFactoryIdAndIpAddress("F-CAM-C", "192.168.1.64")).isEmpty();

        assertThat(iotDeviceDataRepository.findByFactoryIdAndDeviceIdOrderByReceivedAtDesc(
                "F-CAM-A", "scale-1", PageRequest.of(0, 5)))
                .extracting(IotDeviceData::getFactoryId).containsExactly("F-CAM-A");

        assertThat(labelRecognitionRecordRepository.findByFactoryIdAndConfigIdOrderByRecognitionTimeDesc(
                "F-CAM-B", 5L, PageRequest.of(0, 5)).getContent())
                .extracting(LabelRecognitionRecord::getFactoryId).containsExactly("F-CAM-B");
    }
}
