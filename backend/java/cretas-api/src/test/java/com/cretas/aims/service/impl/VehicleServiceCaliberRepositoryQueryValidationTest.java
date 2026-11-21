package com.cretas.aims.service.impl;

import com.cretas.aims.service.inventory.TradeJpaQueryTestSupport;

import com.cretas.aims.dto.warehouse.VehicleDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.VehicleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 车辆口径 (G12): 载重吨↔kg、车牌大小写与首尾空白. 真实 JPA Context + 真实 Repository. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class VehicleServiceCaliberRepositoryQueryValidationTest extends TradeJpaQueryTestSupport {

    private static final String F = "IT-VEH";
    @Autowired private VehicleRepository repo;
    @PersistenceContext private EntityManager em;
    private VehicleServiceImpl service;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        service = new VehicleServiceImpl(repo);
    }

    private VehicleDTO req(String plate, String kg, String tons) {
        VehicleDTO d = new VehicleDTO();
        d.setPlateNumber(plate);
        d.setCapacity(kg == null ? null : new BigDecimal(kg));
        d.setCapacityTons(tons == null ? null : new BigDecimal(tons));
        return d;
    }

    @Test
    void tonsOnlyIsStoredAsKgAndReadBackAsBoth() {
        VehicleDTO saved = service.createVehicle(F, req("沪a12345", null, "2.5"));
        assertThat(saved.getCapacity()).isEqualByComparingTo("2500");
        assertThat(saved.getCapacityTons()).isEqualByComparingTo("2.5");
        assertThat(repo.findById(saved.getId()).orElseThrow().getCapacity()).isEqualByComparingTo("2500");
    }

    @Test
    void kgOnlyStillWorksAndConflictingKgAndTonsRejected() {
        VehicleDTO saved = service.createVehicle(F, req("沪B00001", "3000", null));
        assertThat(saved.getCapacityTons()).isEqualByComparingTo("3");
        assertThatThrownBy(() -> service.createVehicle(F, req("沪B00002", "3000", "2")))
                .isInstanceOf(BusinessException.class);
        assertThat(repo.findByFactoryId(F)).hasSize(1); // 被拒的没落库
    }

    @Test
    void platesAreTrimmedUppercasedAndDuplicatesCaseInsensitive() {
        VehicleDTO a = service.createVehicle(F, req("  沪a12345 ", "1000", null));
        assertThat(a.getPlateNumber()).isEqualTo("沪A12345");
        assertThatThrownBy(() -> service.createVehicle(F, req("沪A12345", "1000", null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createVehicle(F, req(" 沪a12345", "1000", null)))
                .isInstanceOf(BusinessException.class);
        // 别的厂同车牌不算重复
        assertThat(service.createVehicle("OTHER", req("沪A12345", "1000", null)).getId()).isNotNull();
    }

    @Test
    void updateKeepsOwnPlateButRejectsAnothersAndConvertsTons() {
        VehicleDTO a = service.createVehicle(F, req("沪A00001", "1000", null));
        service.createVehicle(F, req("沪A00002", "1000", null));

        VehicleDTO same = service.updateVehicle(F, a.getId(), req(" 沪a00001", null, "4"));
        assertThat(same.getPlateNumber()).isEqualTo("沪A00001");
        assertThat(same.getCapacity()).isEqualByComparingTo("4000");

        assertThatThrownBy(() -> service.updateVehicle(F, a.getId(), req("沪a00002", null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void fractionalKgRoundTripsAndEquivalentLegacyPlateIsNormalizedInDatabase() {
        String id = service.createVehicle(F, req("沪A56001", "1234.56", null)).getId();
        var legacy = repo.findByIdAndFactoryId(id, F).orElseThrow();
        legacy.setPlateNumber(" 沪a56001 ");
        repo.saveAndFlush(legacy);
        em.clear();

        VehicleDTO read = service.getVehicle(F, id);
        assertThat(read.getPlateNumber()).isEqualTo(" 沪a56001 ");
        assertThat(read.getCapacity()).isEqualByComparingTo("1234.56");
        assertThat(read.getCapacityTons()).isEqualByComparingTo("1.23456");
        VehicleDTO updated = service.updateVehicle(F, id, read);
        assertThat(updated.getCapacity()).isEqualByComparingTo("1234.56");

        em.flush();
        em.clear();
        var persisted = repo.findByIdAndFactoryId(id, F).orElseThrow();
        assertThat(persisted.getPlateNumber()).isEqualTo("沪A56001");
        assertThat(persisted.getCapacity()).isEqualByComparingTo("1234.56");
    }

    @Test
    void blankPlateRejected() {
        assertThatThrownBy(() -> service.createVehicle(F, req("   ", null, null)))
                .isInstanceOf(BusinessException.class);
    }
}
