package com.cretas.aims.service.secrecy;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingAuditRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审查 #7: 改设置与写留痕在同一个真实事务里 (经 Spring 代理, 不是测试事务包着) —— 留痕写不进去时设置一起回滚;
 * 阳性对照: 正常时两行都提交。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({FactorySecrecySettingsService.class, PlatformFactoryControlAuthority.class, FactoryFeatureMenuWriteLock.class,
        ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class, ProcessSecretSampleFilter.class,
        FactorySecrecyWriteTransactionJpaContractTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FactorySecrecyWriteTransactionJpaContractTest {

    @TestConfiguration
    static class Config {
        @Bean
        FactorySecrecyConfig factorySecrecyConfig(FactorySecrecySettingRepository repository, FactoryRepository factories) {
            return new FactorySecrecyConfig(repository, factories, (org.springframework.transaction.PlatformTransactionManager) null);
        }
    }

    @Autowired FactorySecrecySettingsService service;
    @Autowired FactorySecrecySettingRepository settings;
    @Autowired FactorySecrecySettingAuditRepository audits;
    @Autowired FactoryRepository factories;
    @Autowired PlatformAdminRepository admins;
    @Autowired FactorySecrecyConfig config;

    String factoryId;
    ActorRef actor;

    @BeforeEach
    void seed() {
        factoryId = "F-TX-" + UUID.randomUUID().toString().substring(0, 8);
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("secrecy tx");
        factories.saveAndFlush(factory);
        PlatformAdmin admin = PlatformAdmin.builder().username("sec_tx_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("x").realName("超管").platformRole(PlatformRole.super_admin)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, admins.saveAndFlush(admin).getId());
    }

    @AfterEach
    void cleanUp() {
        audits.deleteAll(audits.findTop20ByFactoryIdOrderByChangedAtDescIdDesc(factoryId));
        settings.deleteAll(settings.findByFactoryId(factoryId));
        factories.deleteById(factoryId);
        admins.deleteById(actor.id());
    }

    @Test
    void whenTheAuditRowCannotBeWrittenTheSettingRollsBackToo() {
        String tooLongRole = "r".repeat(80);   // actor_role 列 50 字 —— 留痕写入失败
        assertThatThrownBy(() -> service.update(factoryId, "PROCESS_SECRET",
                new FactorySecrecySettingsService.Update(true, null, "客户要求", null), actor, tooLongRole))
                .isInstanceOf(RuntimeException.class);
        assertThat(settings.findByFactoryId(factoryId)).as("设置跟着回滚").isEmpty();
        assertThat(audits.findTop20ByFactoryIdOrderByChangedAtDescIdDesc(factoryId)).isEmpty();
        config.invalidate(factoryId);
        assertThat(config.isOn(factoryId, SecrecyItem.PROCESS_SECRET)).isFalse();
    }

    @Test
    void aNormalWriteCommitsTheSettingAndItsAuditRowTogether() {
        service.update(factoryId, "PROCESS_SECRET",
                new FactorySecrecySettingsService.Update(true, null, "客户要求", null), actor, "super_admin");
        assertThat(settings.findByFactoryId(factoryId)).singleElement()
                .satisfies(s -> assertThat(s.getEnabled()).isTrue());
        assertThat(audits.findTop20ByFactoryIdOrderByChangedAtDescIdDesc(factoryId)).singleElement()
                .satisfies(a -> assertThat(a.getNewValue()).isEqualTo("ON"));
        assertThat(config.isOn(factoryId, SecrecyItem.PROCESS_SECRET)).isTrue();
    }
}
