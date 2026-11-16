package com.cretas.aims.service.integration;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.integration.ExternalSourceSnapshot;
import com.cretas.aims.entity.integration.FactoryExternalIntegration;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.integration.ExternalSourceSnapshotRepository;
import com.cretas.aims.repository.integration.FactoryExternalIntegrationRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一原型迁移 P10 在真实 JPA Context (Hibernate + 真实派生查询) 上: 推送只认绑定在授权行上的令牌、
 * 只写本厂快照, 白名单外字段整批拒收; 读接口按 factoryId 过滤并校验授权行; 有授权没数据 ≠ 0。
 * LSM = 有黑湖授权的工厂 (六膳门角色), F6 = 没授权的工厂 (F006 角色, 阴性对照)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({ExternalIntegrationService.class, com.cretas.aims.service.unit.impl.UnitContractServiceImpl.class,
        ExternalIntegrationJpaContractTest.Config.class})
class ExternalIntegrationJpaContractTest {

    static final String LSM = "F-EXT-LSM";
    static final String F6 = "F-EXT-F6";
    static final String ORG = "1662664";
    static final ActorRef PLATFORM = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 1L);
    static final Instant NOW = Instant.parse("2026-11-15T02:00:00Z");

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired ExternalIntegrationService service;
    @Autowired FactoryExternalIntegrationRepository integrations;
    @Autowired ExternalSourceSnapshotRepository snapshots;
    @Autowired ProductionReportRepository reports;
    @Autowired WorkProcessTaskRepository tasks;
    @Autowired WorkProcessRepository processes;
    @Autowired EntityManager em;
    @org.springframework.boot.test.mock.mockito.SpyBean com.cretas.aims.service.unit.UnitContractService unitContract;

    @BeforeEach
    void fixedClock() {
        // service 是 @Transactional 的 CGLIB 代理: 往代理上写字段, 真正干活的目标对象仍用系统时钟,
        // 夹具时点一过 STALE_HOURS (36h) 断言就会翻红 (2026-11-16 20:01 北京时间起)。必须写到目标对象上。
        ExternalIntegrationService target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
        target.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // 证明固定时钟真的生效: 用系统时钟时, NOW 这个时点在 36h 后会被算成过期
        assertThat(target.isStale(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))).isFalse();
    }

    private String authorize(String factoryId) {
        service.upsert(factoryId, "blacklake", new ExternalIntegrationService.Update("ACTIVE", ORG, "P10 测试"), PLATFORM);
        return service.issuePushToken(factoryId, "blacklake", PLATFORM).token();
    }

    private static Map<String, Object> report(String date, String product, String process, String good) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("reportDate", date);
        r.put("productCode", "CP-" + product);
        r.put("productName", product);
        r.put("processName", process);
        r.put("unit", "KG");
        r.put("goodQty", good);
        r.put("defectQty", "0");
        r.put("reportCount", 1);
        return r;
    }

    private static ExternalIntegrationService.PushRequest request(String collectedAt, List<Map<String, Object>> rows) {
        return new ExternalIntegrationService.PushRequest("blacklake", ORG, collectedAt, "run-1",
                Map.of("report_output", rows));
    }

    @Test
    void aPushLandsOnlyInTheTokensFactoryAndTheNegativeFactorySeesNothing() {
        String token = authorize(LSM);
        var result = service.push(token, request("2026-11-15T08:01:00+08:00",
                List.of(report("2026-11-14", "烟熏牛肉", "腌制", "100.5"))));

        assertThat(result.factoryId()).isEqualTo(LSM);
        assertThat(result.collectedAt()).isEqualTo("2026-11-15 08:01");
        em.flush();
        em.clear();
        ExternalSourceSnapshot stored = snapshots.findFirstByFactoryIdAndSystemAndDatasetOrderByCollectedAtDesc(
                LSM, "blacklake", "report_output").orElseThrow();
        assertThat(stored.getCollectedAt()).isEqualTo(java.time.LocalDateTime.parse("2026-11-15T00:01:00"));
        assertThat(stored.getRowCount()).isEqualTo(1);

        // 授权工厂: 看得到时点、没过期
        var view = service.factoryView(LSM);
        assertThat(view).singleElement().satisfies(v -> {
            assertThat(v.connected()).isTrue();
            assertThat(v.lastCollectedAt()).isEqualTo("2026-11-15 08:01");
            assertThat(v.stale()).isFalse();
        });
        assertThat(service.featureConfigView(LSM)).containsKey("blacklake");

        // 阴性对照: 没授权的工厂什么都不出现, 对照接口 404
        assertThat(service.factoryView(F6)).isEmpty();
        assertThat(service.featureConfigView(F6)).isEmpty();
        assertThatThrownBy(() -> service.reportComparison(F6, "blacklake"))
                .isInstanceOf(BusinessException.class).hasMessage(ExternalIntegrationService.NOT_AUTHORIZED);
    }

    @Test
    void anotherFactoryWithItsOwnAuthorizationDoesNotReadThisFactorysSnapshot() {
        String token = authorize(LSM);
        authorize(F6);
        service.push(token, request("2026-11-15T08:01:00+08:00", List.of(report("2026-11-14", "牛肉", "腌制", "1"))));
        em.flush();

        var comparison = service.reportComparison(F6, "blacklake");
        assertThat(comparison.available()).isFalse();
        assertThat(comparison.reason()).isEqualTo(ExternalIntegrationService.NOT_CONNECTED);
        assertThat(service.factoryView(F6)).singleElement().satisfies(v -> assertThat(v.connected()).isFalse());
        assertThat(service.reportComparison(LSM, "blacklake").available()).isTrue();
    }

    @Test
    void authorizedButNoDataSaysNotConnectedInsteadOfZero() {
        authorize(LSM);
        var comparison = service.reportComparison(LSM, "blacklake");
        assertThat(comparison.available()).isFalse();
        assertThat(comparison.reason()).isEqualTo("外部数据未接入（找平台）");
        assertThat(comparison.rows()).isEmpty();
        assertThat(service.factoryView(LSM)).singleElement().satisfies(v -> {
            assertThat(v.connected()).isFalse();
            assertThat(v.reason()).isEqualTo(ExternalIntegrationService.NOT_CONNECTED);
            assertThat(v.lastCollectedAt()).isNull();
        });
    }

    @Test
    void personnelOrUnknownFieldsRejectTheWholeBatch() {
        String token = authorize(LSM);
        Map<String, Object> leaky = new HashMap<>(report("2026-11-14", "牛肉", "腌制", "1"));
        leaky.put("productionWorkerKey", "W-1");
        assertThatThrownBy(() -> service.push(token, request("2026-11-15T08:01:00+08:00",
                List.of(report("2026-11-14", "羊排", "腌制", "2"), leaky))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("productionWorkerKey");
        em.flush();
        assertThat(snapshots.findAll()).isEmpty();
    }

    @Test
    void suspendedAuthorizationWrongOrgOrBadTokenIsRefused() {
        String token = authorize(LSM);
        var rows = List.of(report("2026-11-14", "牛肉", "腌制", "1"));

        assertThatThrownBy(() -> service.push("cxs_not-a-real-token", request("2026-11-15T08:01:00+08:00", rows)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
        assertThatThrownBy(() -> service.push(token, new ExternalIntegrationService.PushRequest("blacklake", "999",
                "2026-11-15T08:01:00+08:00", null, Map.of("report_output", rows))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);

        service.upsert(LSM, "blacklake", new ExternalIntegrationService.Update("SUSPENDED", ORG, null), PLATFORM);
        em.flush();
        // 暂停同时作废凭证 (审查中1): 旧凭证 = 不认识 = 401
        assertThatThrownBy(() -> service.push(token, request("2026-11-15T08:01:00+08:00", rows)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
        // 暂停期间签发的凭证: 认识, 但授权行不是 ACTIVE → 403
        String issuedWhileSuspended = service.issuePushToken(LSM, "blacklake", PLATFORM).token();
        em.flush();
        assertThatThrownBy(() -> service.push(issuedWhileSuspended, request("2026-11-15T08:01:00+08:00", rows)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        // 暂停后工厂端不再出现
        assertThat(service.factoryView(LSM)).isEmpty();
        assertThat(service.featureConfigView(LSM)).isEmpty();
        assertThatThrownBy(() -> service.reportComparison(LSM, "blacklake")).isInstanceOf(BusinessException.class);
        assertThat(snapshots.findAll()).isEmpty();
    }

    /**
     * 审查中1: 暂停 = 撤销凭证。暂停后再授权, 旧凭证必须是 401 (不认识), 不是复活;
     * 平台视图显示「没有凭证」, 重新签发的新凭证才能推。
     */
    @Test
    void suspendingRevokesTheTokenSoReauthorizingDoesNotReviveIt() {
        String old = authorize(LSM);
        service.upsert(LSM, "blacklake", new ExternalIntegrationService.Update("SUSPENDED", ORG, null), PLATFORM);
        em.flush();
        em.clear();
        FactoryExternalIntegration suspended = integrations.findByFactoryIdAndSystem(LSM, "blacklake").orElseThrow();
        assertThat(suspended.getPushTokenSha256()).isNull();
        assertThat(suspended.getPushTokenIssuedAt()).isNull();

        service.upsert(LSM, "blacklake", new ExternalIntegrationService.Update("ACTIVE", ORG, "恢复"), PLATFORM);
        em.flush();
        em.clear();
        var rows = List.of(report("2026-11-14", "牛肉", "腌制", "1"));
        assertThatThrownBy(() -> service.push(old, request("2026-11-15T08:01:00+08:00", rows)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
        assertThat(service.platformView(LSM)).singleElement()
                .satisfies(r -> assertThat(r.hasPushToken()).isFalse());
        assertThat(snapshots.findAll()).isEmpty();

        String fresh = service.issuePushToken(LSM, "blacklake", PLATFORM).token();
        assertThat(service.push(fresh, request("2026-11-15T08:01:00+08:00", rows)).factoryId()).isEqualTo(LSM);
    }

    /** 控制器限流前的凭证确认: 认出授权行才给分桶键; 无效 401, 暂停 403 (都不会占限流桶)。 */
    @Test
    void authenticatePushResolvesTheAuthorizationOrRefuses() {
        String token = authorize(LSM);
        assertThat(service.authenticatePush(token)).isEqualTo(new ExternalIntegrationService.PushPrincipal(LSM, "blacklake"));
        assertThat(service.authenticatePush("  " + token + " ").factoryId()).isEqualTo(LSM);
        for (String bad : new String[]{null, "", "Bearer x", "cxs_not-a-real-token"}) {
            assertThatThrownBy(() -> service.authenticatePush(bad))
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
        }
        String issuedWhileSuspended;
        service.upsert(LSM, "blacklake", new ExternalIntegrationService.Update("SUSPENDED", ORG, null), PLATFORM);
        issuedWhileSuspended = service.issuePushToken(LSM, "blacklake", PLATFORM).token();
        em.flush();
        assertThatThrownBy(() -> service.authenticatePush(issuedWhileSuspended))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
    }

    @Test
    void rotatingTheTokenRevokesTheOldOne() {
        String old = authorize(LSM);
        String fresh = service.issuePushToken(LSM, "blacklake", PLATFORM).token();
        em.flush();
        var rows = List.of(report("2026-11-14", "牛肉", "腌制", "1"));
        assertThatThrownBy(() -> service.push(old, request("2026-11-15T08:01:00+08:00", rows)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
        assertThat(service.push(fresh, request("2026-11-15T08:01:00+08:00", rows)).factoryId()).isEqualTo(LSM);
        FactoryExternalIntegration row = integrations.findByFactoryIdAndSystem(LSM, "blacklake").orElseThrow();
        assertThat(row.getPushTokenSha256()).hasSize(64).doesNotContain(fresh);
    }

    @Test
    void samePointRepushIsIdempotentDifferentContentIsRefused() {
        String token = authorize(LSM);
        var rows = List.of(report("2026-11-14", "牛肉", "腌制", "1"));
        service.push(token, request("2026-11-15T08:01:00+08:00", rows));
        em.flush();
        assertThat(service.push(token, request("2026-11-15T00:01:00Z", rows)).datasets())
                .singleElement().satisfies(d -> assertThat(d.duplicate()).isTrue());
        assertThatThrownBy(() -> service.push(token, request("2026-11-15T08:01:00+08:00",
                List.of(report("2026-11-14", "牛肉", "腌制", "2")))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(snapshots.findAll()).hasSize(1);
    }

    @Test
    void olderThan36HoursIsMarkedStale() {
        String token = authorize(LSM);
        service.push(token, request("2026-11-13T09:00:00+08:00", List.of(report("2026-11-12", "牛肉", "腌制", "1"))));
        em.flush();
        assertThat(service.factoryView(LSM)).singleElement().satisfies(v -> assertThat(v.stale()).isTrue());
        assertThat(service.reportComparison(LSM, "blacklake").stale()).isTrue();
        assertThat(service.featureConfigView(LSM).get("blacklake")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("stale", true);
    }

    @Test
    void comparisonPutsCretasWeighingNextToExternalReportsByDateProductAndProcess() {
        String token = authorize(LSM);
        service.push(token, request("2026-11-15T08:01:00+08:00", List.of(
                report("2026-11-14", "烟熏牛肉", "腌制", "100.000"),
                report("2026-11-14", "烟熏牛肉", "烟熏", "80.000"),
                report("2026-11-13", "羊排", "腌制", "50"),
                withUnit(report("2026-11-11", "鸡翅", "腌制", "30"), "公斤"),
                withUnit(report("2026-11-10", "鸡翅", "腌制", "30"), "箱"),
                withUnit(report("2026-11-09", "鸡翅", "腌制", "4"), "托"),
                withUnit(report("2026-11-08", "鸡翅", "腌制", "6"), "只"))));

        WorkProcess pickle = processes.save(WorkProcess.builder().id("WP-EXT-1").factoryId(LSM).processName("腌制")
                .defaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED).build());
        WorkProcess smoke = processes.save(WorkProcess.builder().id("WP-EXT-2").factoryId(LSM).processName("烟熏")
                .defaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED).build());
        // 别厂同名工序 + 同日同产品报工: 不能混进来
        WorkProcess other = processes.save(WorkProcess.builder().id("WP-EXT-3").factoryId(F6).processName("腌制")
                .defaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED).build());
        Long t1 = task(LSM, pickle.getId());
        Long t2 = task(LSM, smoke.getId());
        Long t3 = task(F6, other.getId());
        yieldReport(LSM, t1, "2026-11-14", "烟熏牛肉", "100.2", "kg");    // 差 0.2% → 一致
        yieldReport(LSM, t2, "2026-11-14", "烟熏牛肉", "70", "kg");       // 差 -10 → 有差异
        yieldReport(LSM, t1, "2026-11-12", "鸡腿", "5", "kg");            // 只有 Cretas
        yieldReport(F6, t3, "2026-11-13", "羊排", "50", "kg");            // 别厂: 不算
        yieldReport(LSM, t1, "2026-11-11", "鸡翅", "30", "kg");           // 公斤 ≡ kg (单位契约) → 一致
        yieldReport(LSM, t1, "2026-11-10", "鸡翅", "30", "kg");           // 箱 ≠ kg → 单位不同, 不算差
        yieldReport(LSM, t1, "2026-11-09", "鸡翅", "4", "托");            // 两边同一个未登记单位 → 照常比
        yieldReport(LSM, t1, "2026-11-08", "鸡翅", "6", "件");            // 只 ≠ 件 (Steve 08-03: 只/个/件 三个单位)
        em.flush();
        em.clear();

        var c = service.reportComparison(LSM, "blacklake");
        assertThat(c.available()).isTrue();
        assertThat(c.asOf()).isEqualTo("2026-11-15 08:01");
        assertThat(c.from()).isEqualTo(LocalDate.parse("2026-11-01"));
        Map<String, ExternalIntegrationService.ComparisonRow> byKey = new HashMap<>();
        c.rows().forEach(r -> byKey.put(r.reportDate() + "/" + r.productName() + "/" + r.processName(), r));
        assertThat(byKey).hasSize(8);
        assertThat(byKey.get("2026-11-09/鸡翅/腌制").state()).isEqualTo(ExternalIntegrationService.MatchState.MATCH);
        assertThat(byKey.get("2026-11-08/鸡翅/腌制")).satisfies(r -> {
            assertThat(r.state()).isEqualTo(ExternalIntegrationService.MatchState.UNIT_MISMATCH);
            assertThat(r.difference()).isNull();
        });
        // 每个不同的原始写法只问一次单位契约 (kg 公斤 箱 托 只 件), 不是每行每次都查
        org.mockito.Mockito.verify(unitContract, org.mockito.Mockito.atMost(6))
                .storageUnit(org.mockito.ArgumentMatchers.eq(LSM), org.mockito.ArgumentMatchers.anyString());
        assertThat(byKey.get("2026-11-11/鸡翅/腌制")).satisfies(r -> {
            assertThat(r.state()).isEqualTo(ExternalIntegrationService.MatchState.MATCH);
            assertThat(r.difference()).isEqualByComparingTo("0");
        });
        assertThat(byKey.get("2026-11-10/鸡翅/腌制")).satisfies(r -> {
            assertThat(r.state()).isEqualTo(ExternalIntegrationService.MatchState.UNIT_MISMATCH);
            assertThat(r.difference()).isNull();
        });
        assertThat(byKey.get("2026-11-14/烟熏牛肉/腌制").state()).isEqualTo(ExternalIntegrationService.MatchState.MATCH);
        assertThat(byKey.get("2026-11-14/烟熏牛肉/烟熏")).satisfies(r -> {
            assertThat(r.state()).isEqualTo(ExternalIntegrationService.MatchState.DIFF);
            assertThat(r.difference()).isEqualByComparingTo("-10");
        });
        assertThat(byKey.get("2026-11-13/羊排/腌制").state()).isEqualTo(ExternalIntegrationService.MatchState.EXTERNAL_ONLY);
        assertThat(byKey.get("2026-11-13/羊排/腌制").cretasGoodQty()).isNull();
        assertThat(byKey.get("2026-11-12/鸡腿/腌制").state()).isEqualTo(ExternalIntegrationService.MatchState.CRETAS_ONLY);
    }

    private static Map<String, Object> withUnit(Map<String, Object> row, String unit) {
        row.put("unit", unit);
        return row;
    }

    private Long task(String factoryId, String processId) {
        WorkProcessTask t = new WorkProcessTask();
        t.setFactoryId(factoryId);
        t.setProductionBatchId(1L);
        t.setWorkProcessId(processId);
        t.setProductTypeId("PT-EXT");
        t.setProcessOrder(1);
        return tasks.save(t).getId();
    }

    private void yieldReport(String factoryId, Long taskId, String date, String product, String qty, String unit) {
        reports.save(ProductionReport.builder().factoryId(factoryId).workerId(1L).reportType("YIELD")
                .reportDate(LocalDate.parse(date)).productName(product).workProcessTaskId(taskId)
                .outputQuantity(new BigDecimal(qty)).outputUnit(unit).build());
    }
}
