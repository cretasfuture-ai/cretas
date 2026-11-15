package com.cretas.aims.repository.intent;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1771 复审 中-A / 中-D: 数字班组事项被别人重新预览时, 按行 id 作废上一个预览人的凭据
 * ({@link IntentPreviewTokenRepository#cancelPendingById})。H2 默认; 设了 CRETAS_WORKFLOW_PG_URL + VERIFY 时在真 PG 上跑
 * (已登记 java-gate-pg-tests.txt)。
 *
 * <ul>
 *   <li>只作废属主 + 工厂 (+ 租户) 对得上、还是 PENDING 的; 作废后这张凭据在任何入口都认领不到; 已被认领的作废不了。</li>
 *   <li>两个线程同时一个认领 ({@code claimForExecution})、一个作废, 各自一个事务: 只有一方生效, 最终状态与生效方一致
 *       —— 不会出现「认领成功又被改成已取消」或「作废成功又被认领执行」。</li>
 * </ul>
 * 事务不包住测试 (NOT_SUPPORTED): 每个操作自己提交, 两个线程看到的是真实的并发。
 */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = IntentPreviewToken.class)
@EnableJpaRepositories(basePackageClasses = IntentPreviewTokenRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = IntentPreviewTokenRepository.class))
class IntentPreviewTokenCancelByIdRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        boolean hasUrl = url != null && !url.isBlank();
        boolean verify = "true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"));
        if (hasUrl != verify) {
            throw new IllegalStateException("CRETAS_WORKFLOW_PG_URL and CRETAS_WORKFLOW_PG_VERIFY=true must be set together");
        }
        if (hasUrl) {
            String safe = DisposablePostgresTargetGuard.requireSafeUrl(url);
            String bound = safe.contains("stringtype=") ? safe : safe + (safe.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    private static final String F = "F-JPA-CANCEL";
    private static final long OWNER = 42L;

    @Autowired IntentPreviewTokenRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> repository.deleteAll());
    }

    @Test
    void cancelPendingByIdIsOwnerBoundAndMakesTheTokenUnclaimable() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        IntentPreviewToken first = persistBound("token-first-previewer", now.plusMinutes(5));
        IntentPreviewToken claimed = persistBound("token-already-claimed", now.plusMinutes(5));
        assertThat(claim(claimed, "claim-elsewhere", now)).isEqualTo(1);

        assertThat(cancel(first.getId(), "OTHER", OWNER, now)).as("别的厂作废不了").isZero();
        assertThat(cancel(first.getId(), F, OWNER + 1, now)).as("不是属主作废不了").isZero();
        assertThat(cancel(first.getId(), F, OWNER, now)).isEqualTo(1);
        assertThat(cancel(first.getId(), F, OWNER, now)).as("第二次作废: 已不是 PENDING").isZero();
        assertThat(status(first)).isEqualTo(TokenStatus.CANCELLED);
        assertThat(claim(first, "claim-after-cancel", now)).as("作废后的凭据认领不到").isZero();

        assertThat(cancel(claimed.getId(), F, OWNER, now)).as("已被认领的作废不了, 不会被改回已取消").isZero();
        assertThat(status(claimed)).isEqualTo(TokenStatus.EXECUTING);
    }

    /** 认领与作废并发 (各自一个事务, 同一时刻出发): 每一轮恰好一方生效, 最终状态与生效方一致。 */
    @Test
    void concurrentClaimAndCancelHaveExactlyOneWinner() throws Exception {
        int rounds = 20;
        int claimWins = 0;
        int cancelWins = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int r = 0; r < rounds; r++) {
                LocalDateTime now = LocalDateTime.now();
                IntentPreviewToken t = persistBound("token-race-" + r, now.plusMinutes(5));
                CyclicBarrier go = new CyclicBarrier(2);
                Future<Integer> claim = pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return claim(t, "claim-race", now);
                });
                Future<Integer> cancel = pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return cancel(t.getId(), F, OWNER, now);
                });
                int c = claim.get(30, TimeUnit.SECONDS);
                int x = cancel.get(30, TimeUnit.SECONDS);
                assertThat(c + x).as("第 %d 轮: 认领与作废恰好一方生效", r).isEqualTo(1);
                assertThat(status(t)).as("第 %d 轮: 最终状态与生效方一致", r)
                        .isEqualTo(c == 1 ? TokenStatus.EXECUTING : TokenStatus.CANCELLED);
                claimWins += c;
                cancelWins += x;
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(claimWins + cancelWins).isEqualTo(rounds);
    }

    private int claim(IntentPreviewToken t, String claimId, LocalDateTime now) {
        Integer n = tx.execute(s -> repository.claimForExecution(
                t.getToken(), t.getFactoryId(), t.getTenantId(), t.getUserId(),
                t.getCommandDigest(), t.getParametersHash(), t.getToolName(),
                t.getDescriptorVersion(), t.getExecutionMode(), claimId, now, now,
                TokenStatus.PENDING, TokenStatus.EXECUTING));
        return n == null ? -1 : n;
    }

    private int cancel(Long id, String factoryId, long owner, LocalDateTime now) {
        Integer n = tx.execute(s -> repository.cancelPendingById(id, factoryId, owner, "重新预览", now));
        return n == null ? -1 : n;
    }

    private TokenStatus status(IntentPreviewToken t) {
        return tx.execute(s -> repository.findByToken(t.getToken()).orElseThrow().getStatus());
    }

    private IntentPreviewToken persistBound(String tokenValue, LocalDateTime expiresAt) throws Exception {
        JsonNode parameters = objectMapper.readTree("{\"amount\":1.00,\"enabled\":true}");
        String parametersHash = ToolCommandDigest.parametersHash(parameters);
        String commandDigest = ToolCommandDigest.commandDigest(F, OWNER, "order_create", "1.2.3",
                ToolExecutionMode.EXECUTE, parameters);
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(tokenValue)
                .factoryId(F)
                .tenantId(F)
                .userId(OWNER)
                .intentCode("ORDER_CREATE")
                .toolName("order_create")
                .descriptorVersion("1.2.3")
                .executionMode(ToolExecutionMode.EXECUTE)
                .parametersHash(parametersHash)
                .commandDigest(commandDigest)
                .previewData(objectMapper.writeValueAsString(parameters))
                .status(TokenStatus.PENDING)
                .expiresAt(expiresAt)
                .build();
        return tx.execute(s -> repository.saveAndFlush(token));
    }
}
