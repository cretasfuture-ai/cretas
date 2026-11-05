package com.cretas.aims.service.impl;

import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.PreviewTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

/** Reuses the legacy claim race with the existing narrow, native PostgreSQL context. */
@SpringJUnitConfig(PreviewRevisionInvalidationStartupGuardTest.Config.class)
@EnabledIfEnvironmentVariable(named="CRETAS_SEMANTIC_JPA_URL",
        matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/semantic_context_test[?]stringtype=unspecified")
class PreviewTokenServicePostgresClaimStartupGuardTest {
    @Autowired DataSource dataSource;
    @Autowired ConversationSessionRepository ownerLocks;
    @Autowired IntentPreviewTokenRepository repository;
    @Autowired PreviewTokenService previewTokens;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void concurrentLegacyClaimsUseTheActualPostgresOwnerLock() throws Exception {
        try(var connection=dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(mockingDetails(ownerLocks).isMock()).isFalse();
        var tx=new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status->repository.deleteAll());
        var token=previewTokens.createBoundToken(new BoundTokenRequest("F-CONCURRENT",77L,"runner",
                "ORDER_CREATE","Create order","order_create","1.0.0",ToolExecutionMode.EXECUTE,
                "ORDER","O-77","CREATE",Map.of("amount",7),Map.of(),Map.of(),300));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<ClaimResult> claim=()->{
                ready.countDown();
                if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("race start timed out");
                return previewTokens.claimToken(token.getToken(),"F-CONCURRENT",77L);
            };
            var first=executor.submit(claim);var second=executor.submit(claim);
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            var outcomes=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(ClaimResult::isSuccess).hasSize(1);
            assertThat(outcomes).filteredOn(result->!result.isSuccess()).singleElement()
                    .satisfies(result->assertThat(result.getMessage()).containsAnyOf("其他确认请求","已被处理"));
            var winner=outcomes.stream().filter(ClaimResult::isSuccess).findFirst().orElseThrow();
            assertThat(previewTokens.resolveClaim(token.getToken(),winner.getClaimId(),true,"done")).isTrue();
            var persisted=tx.execute(status->repository.findByToken(token.getToken()).orElseThrow());
            assertThat(persisted.getStatus()).isEqualTo(com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus.CONFIRMED);
        } finally {
            start.countDown();executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);
            tx.executeWithoutResult(status->repository.deleteAll());
        }
    }
}
