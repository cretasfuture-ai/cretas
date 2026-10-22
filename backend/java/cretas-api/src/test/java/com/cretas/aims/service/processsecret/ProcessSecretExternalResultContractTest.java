package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processsecret.ProcessSecretExternalPackage;
import com.cretas.aims.entity.processsecret.ProcessSecretExternalResult;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalPackageRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalResultRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B 档边界契约 (方案 §6.2 / 验收 B 系列): 只收引用与公钥; 结果必须签名、绑定批次与版本、幂等、待核验。 */
class ProcessSecretExternalResultContractTest {

    static final String F = "F-A";
    static final String P = "P17";
    static final long OWNER = 10L;

    ProcessSecretPolicyRepository policies;
    ProcessSecretExternalPackageRepository packages;
    ProcessSecretExternalResultRepository results;
    ProductionBatchRepository batches;
    BomRecipeRepository recipes;
    ProcessSecretPolicyService policyService;
    ProcessSecretAccessService access;
    ProcessSecretExternalService service;
    KeyPair executorKey;
    String publicKey;
    ProcessSecretExternalPackage pkg;

    @BeforeEach
    void setUp() throws Exception {
        policies = mock(ProcessSecretPolicyRepository.class);
        packages = mock(ProcessSecretExternalPackageRepository.class);
        results = mock(ProcessSecretExternalResultRepository.class);
        batches = mock(ProductionBatchRepository.class);
        recipes = mock(BomRecipeRepository.class);
        access = mock(ProcessSecretAccessService.class);
        when(access.audit(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        policyService = new ProcessSecretPolicyService(policies, mock(
                com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository.class),
                mock(com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository.class),
                mock(com.cretas.aims.repository.UserRepository.class), access);
        service = new ProcessSecretExternalService(policies, packages, results, batches, recipes, policyService, access);

        executorKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKey = Base64.getEncoder().encodeToString(executorKey.getPublic().getEncoded());
        pkg = ProcessSecretExternalPackage.builder().id(5L).factoryId(F).productTypeId(P)
                .packageRef("P17-PREMIX").packageVersion("v2").outputUnit("kg").executorPublicKey(publicKey)
                .createdBy(OWNER).build();
        when(packages.findByFactoryIdAndPackageRefAndPackageVersion(F, "P17-PREMIX", "v2")).thenReturn(Optional.of(pkg));
        ProductionBatch batch = new ProductionBatch();
        batch.setId(300L);
        batch.setFactoryId(F);
        batch.setProductTypeId(P);
        when(batches.findByIdAndFactoryId(300L, F)).thenReturn(Optional.of(batch));
        when(results.findByFactoryIdAndEventId(any(), any())).thenReturn(Optional.empty());
        when(results.save(any(ProcessSecretExternalResult.class))).thenAnswer(i -> i.getArgument(0));
        when(results.saveAndFlush(any(ProcessSecretExternalResult.class))).thenAnswer(i -> i.getArgument(0));
        policy(ProcessSecretStorageMode.CUSTOMER_HELD);
        when(packages.save(any(ProcessSecretExternalPackage.class))).thenAnswer(i -> i.getArgument(0));
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    void policy(ProcessSecretStorageMode mode) {
        ProcessSecretPolicy p = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(P)
                .storageMode(mode).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, P)).thenReturn(Optional.of(p));
    }

    String now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    ProcessSecretExternalService.ResultRequest signed(String eventId, String qty, String unit, long batchId,
                                                      KeyPair key) throws Exception {
        ProcessSecretExternalService.ResultRequest unsigned = new ProcessSecretExternalService.ResultRequest(
                "P17-PREMIX", "v2", eventId, batchId, new BigDecimal(qty), unit, now(), "x");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(ProcessSecretExternalService.canonicalPayload(F, unsigned).getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        return new ProcessSecretExternalService.ResultRequest(unsigned.packageRef(), unsigned.packageVersion(),
                eventId, batchId, unsigned.outputQuantity(), unit, unsigned.signedAt(), sig);
    }

    static void assertCode(int code, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertEquals(code, e.getCode(), e.getMessage());
    }

    @Test
    void registerOnlyForCustomerHeldByOwnerWithValidEd25519Key() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        var req = new ProcessSecretExternalService.PackageRequest(P, "P17-PREMIX", "v3", "kg", publicKey);
        assertCode(409, () -> service.registerPackage(user(OWNER), F, req));

        policy(ProcessSecretStorageMode.CUSTOMER_HELD);
        assertCode(403, () -> service.registerPackage(user(77), F, req));
        assertCode(400, () -> service.registerPackage(user(OWNER), F,
                new ProcessSecretExternalService.PackageRequest(P, "P17-PREMIX", "v3", "kg", "not-a-key")));
        assertCode(400, () -> service.registerPackage(user(OWNER), F,
                new ProcessSecretExternalService.PackageRequest(P, "配方/原文", "v3", "kg", publicKey)));
        var view = service.registerPackage(user(OWNER), F, req);
        assertThat(view.packageRef()).isEqualTo("P17-PREMIX");
    }

    @Test
    void validSignedResultIsStoredPendingReviewAndReplayIsIdempotent() throws Exception {
        var req = signed("evt-00000001", "12.500", "kg", 300L, executorKey);
        var first = service.submitResult(user(20), F, req);
        assertThat(first.reviewStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(first.duplicate()).isFalse();

        ProcessSecretExternalResult stored = ProcessSecretExternalResult.builder().id(1L).factoryId(F).packageId(5L)
                .eventId("evt-00000001").productionBatchId(300L).outputQuantity(new BigDecimal("12.500"))
                .outputUnit("kg").payloadDigest(sha(ProcessSecretExternalService.canonicalPayload(F, req)))
                .submittedBy(20L).build();
        when(results.findByFactoryIdAndEventId(F, "evt-00000001")).thenReturn(Optional.of(stored));
        var again = service.submitResult(user(20), F, req);
        assertThat(again.duplicate()).isTrue();

        var changed = signed("evt-00000001", "99", "kg", 300L, executorKey);
        assertCode(409, () -> service.submitResult(user(20), F, changed));
    }

    @Test
    void forgedOrTamperedSignatureIsRejectedAndNothingStored() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertCode(403, () -> service.submitResult(user(20), F, signed("evt-00000002", "5", "kg", 300L, attacker)));

        var good = signed("evt-00000003", "5", "kg", 300L, executorKey);
        var tampered = new ProcessSecretExternalService.ResultRequest(good.packageRef(), good.packageVersion(),
                good.eventId(), good.productionBatchId(), new BigDecimal("50"), good.outputUnit(), good.signedAt(),
                good.signature());
        assertCode(403, () -> service.submitResult(user(20), F, tampered));
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void bindingMismatchesAreRejected() throws Exception {
        ProductionBatch other = new ProductionBatch();
        other.setId(301L);
        other.setFactoryId(F);
        other.setProductTypeId("OTHER");
        when(batches.findByIdAndFactoryId(301L, F)).thenReturn(Optional.of(other));
        assertCode(409, () -> service.submitResult(user(20), F, signed("evt-00000004", "5", "kg", 301L, executorKey)));
        assertCode(409, () -> service.submitResult(user(20), F, signed("evt-00000005", "5", "g", 300L, executorKey)));
        assertCode(409, () -> service.submitResult(user(20), F, signed("evt-00000006", "5", "kg", 999L, executorKey)));
        assertCode(400, () -> service.submitResult(user(20), F, signed("evt-00000007", "0", "kg", 300L, executorKey)));
        assertCode(403, () -> service.submitResult(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 20L, "F-B", "operator"), F,
                signed("evt-00000008", "5", "kg", 300L, executorKey)));
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void absurdQuantityIsRejectedBeforeBuildingTheCanonicalString() throws Exception {
        // 审查 H4: 1e2147483000 若进 toPlainString() 会 OOM; 必须在拼串 / 查包之前就 400
        var huge = new ProcessSecretExternalService.ResultRequest("P17-PREMIX", "v2", "evt-00000009", 300L,
                new BigDecimal("1e2147483000"), "kg", now(), "x");
        assertCode(400, () -> service.submitResult(user(20), F, huge));
        assertCode(400, () -> service.submitResult(user(20), F, signed("evt-00000010", "1.23456", "kg", 300L,
                executorKey)));
        assertCode(400, () -> service.submitResult(user(20), F, signed("evt-00000011", "123456789012", "kg", 300L,
                executorKey)));
        verify(packages, never()).findByFactoryIdAndPackageRefAndPackageVersion(F, "P17-PREMIX", "v2");
        // 阳性对照: 边界值 11 位整数 + 4 位小数照收
        assertThat(service.submitResult(user(20), F, signed("evt-00000012", "12345678901.1234", "kg", 300L,
                executorKey)).duplicate()).isFalse();
    }

    @Test
    void resultsStopWhenProductLeavesCustomerHeldOrPackageIsRetired() throws Exception {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        assertCode(409, () -> service.submitResult(user(20), F, signed("evt-00000013", "5", "kg", 300L, executorKey)));
        when(policies.findByFactoryIdAndProductTypeId(F, P)).thenReturn(Optional.empty());
        assertCode(409, () -> service.submitResult(user(20), F, signed("evt-00000014", "5", "kg", 300L, executorKey)));

        policy(ProcessSecretStorageMode.CUSTOMER_HELD);
        when(packages.findById(5L)).thenReturn(Optional.of(pkg));
        assertCode(403, () -> service.retirePackage(user(77), F, 5L));
        assertThat(service.retirePackage(user(OWNER), F, 5L).status())
                .isEqualTo(com.cretas.aims.entity.processsecret.ProcessSecretPackageStatus.RETIRED);
        assertCode(409, () -> service.submitResult(user(20), F, signed("evt-00000015", "5", "kg", 300L, executorKey)));
        verify(results, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateEventIs409NotA500() throws Exception {
        when(results.saveAndFlush(any(ProcessSecretExternalResult.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq"));
        assertCode(409, () -> service.submitResult(user(20), F, signed("evt-00000016", "5", "kg", 300L, executorKey)));
    }

    @Test
    void statusFlagsHistoricalCloudCopyForCustomerHeld() {
        policy(ProcessSecretStorageMode.CUSTOMER_HELD);
        when(recipes.countByFactoryIdAndProductTypeId(F, P)).thenReturn(2L);
        assertThat(service.packageStatus(user(20), F, P).cloudCopyStatus())
                .isEqualTo("HISTORICAL_CLOUD_COPY_PENDING_DISPOSAL");
        when(recipes.countByFactoryIdAndProductTypeId(F, P)).thenReturn(0L);
        assertThat(service.packageStatus(user(20), F, P).cloudCopyStatus()).isEqualTo("NONE");
    }

    static String sha(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
