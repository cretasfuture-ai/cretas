package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.entity.EquipmentAlert;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.service.PushNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 按设备查告警: 设备条件在数据库里、分页之前生效, 总数是筛过的 (S6 #1366 后续)。
 * 以前 alert_list / alert_by_equipment 查回一页再在页内按设备筛, 总数仍是全厂的; 第 1 页之后的那台设备的告警读成「没有」。
 */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class EquipmentAlertEquipmentFilterRepositoryQueryValidationTest {

    private static final String F = "IT-ALERT-EQ";

    @Autowired private EquipmentAlertRepository alerts;
    @Autowired private JdbcTemplate jdbc;

    private EquipmentAlertsServiceImpl service;

    @BeforeEach
    void seed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("DELETE FROM equipment_alerts");
        LocalDateTime now = LocalDateTime.now();
        // 设备 1 的告警最新、撑满第一页; 设备 2 只有一条最旧的
        for (int i = 0; i < 3; i++) {
            alert(F, 1L, now.minusMinutes(i));
        }
        alert(F, 2L, now.minusDays(3));
        alert("IT-ALERT-EQ-OTHER", 2L, now);
        service = new EquipmentAlertsServiceImpl(alerts, mock(EquipmentRepository.class),
                mock(PushNotificationService.class));
    }

    @Test
    void anEquipmentWhoseAlertsAreBeyondPageOneIsFoundAndTheTotalIsFiltered() {
        PageResponse<EquipmentAlertDTO> page = service.getAlertList(F, page(1, 2), null, null, null, 2L);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1).extracting(EquipmentAlertDTO::getEquipmentId).containsOnly(2L);
    }

    @Test
    void withoutAnEquipmentTheListIsUnchanged() {
        PageResponse<EquipmentAlertDTO> filtered = service.getAlertList(F, page(1, 2), null, null, null, null);
        PageResponse<EquipmentAlertDTO> original = service.getAlertList(F, page(1, 2), null, null, null);

        assertThat(filtered.getTotalElements()).isEqualTo(4).isEqualTo(original.getTotalElements());
        assertThat(filtered.getContent()).extracting(EquipmentAlertDTO::getEquipmentId).containsOnly(1L);
    }

    private static PageRequest page(int page, int size) {
        PageRequest p = new PageRequest();
        p.setPage(page);
        p.setSize(size);
        return p;
    }

    private void alert(String factoryId, long equipmentId, LocalDateTime at) {
        EquipmentAlert a = new EquipmentAlert();
        a.setFactoryId(factoryId);
        a.setEquipmentId(equipmentId);
        a.setAlertType("维护提醒");
        a.setMessage("设备 " + equipmentId + " 需要维护");
        a.setTriggeredAt(at);
        alerts.saveAndFlush(a);
    }
}
