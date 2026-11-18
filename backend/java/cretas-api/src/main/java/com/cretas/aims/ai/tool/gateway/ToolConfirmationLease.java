package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/** Atomic confirmation claim adapter. It never logs or persists the bearer token. */
@Component
@RequiredArgsConstructor
public class ToolConfirmationLease {

    private final PreviewTokenService previewTokenService;
    private final ObjectMapper objectMapper;

    public Optional<Lease> claim(
            ToolExecutionCommand command,
            ExecutionPrincipal currentPrincipal,
            String expectedCommandDigest) {
        if (command.confirmationProof().isEmpty()) {
            return Optional.empty();
        }
        ConfirmationProof proof = command.confirmationProof().get();
        if (!constantTimeEquals(proof.commandDigest(), expectedCommandDigest)) {
            return Optional.empty();
        }
        // The claim is bound to the business object the caller executes against; a proof that
        // does not say which object is never claimed (fail-closed, nothing is consumed).
        if (!proof.bindsSubject()) {
            return Optional.empty();
        }
        Long userId;
        try {
            userId = Long.valueOf(currentPrincipal.principalId());
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }

        PreviewTokenService.ClaimResult claim = previewTokenService.claimTokenForSubject(
                proof.proofToken(), currentPrincipal.tenantId(), userId, expectedCommandDigest,
                proof.entityType(), proof.entityId());
        if (!claim.isSuccess() || claim.getToken() == null || claim.getClaimId() == null
                || claim.getClaimId().isBlank() || claim.getParameters() == null) {
            return Optional.empty();
        }
        IntentPreviewToken token = claim.getToken();
        JsonNode persistedParameters = objectMapper.valueToTree(claim.getParameters());
        boolean exactBinding = currentPrincipal.tenantId().equals(token.getFactoryId())
                && currentPrincipal.tenantId().equals(token.getTenantId())
                && userId.equals(token.getUserId())
                && command.toolName().equals(token.getToolName())
                && command.expectedDescriptorVersion().equals(token.getDescriptorVersion())
                && command.mode() == token.getExecutionMode()
                && constantTimeEquals(expectedCommandDigest, token.getCommandDigest())
                && proof.entityType().equals(token.getEntityType())
                && java.util.Objects.equals(blankToNull(proof.entityId()), blankToNull(token.getEntityId()));
        if (!exactBinding) {
            previewTokenService.resolveClaim(
                    proof.proofToken(), claim.getClaimId(), false, "gateway binding rejected");
            return Optional.empty();
        }
        return Optional.of(new Lease(
                proof.proofToken(), claim.getClaimId(), persistedParameters.deepCopy(),
                token.getCurrentValues()));
    }

    public boolean resolve(Lease lease, boolean success) {
        return previewTokenService.resolveClaim(
                lease.proofToken(),
                lease.claimId(),
                success,
                success ? "gateway execution completed" : "gateway execution failed");
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @param recordedPriorState the business-state snapshot (JSON) the issuer recorded at preview
     *                           time, or {@code null}/blank/{@code "{}"} when none was recorded; the
     *                           gateway re-checks it before executing ({@link PreviewStateRecheck}).
     */
    public record Lease(String proofToken, String claimId, JsonNode persistedParameters,
                        String recordedPriorState) {
        public Lease {
            persistedParameters = persistedParameters.deepCopy();
        }

        public Lease(String proofToken, String claimId, JsonNode persistedParameters) {
            this(proofToken, claimId, persistedParameters, null);
        }

        @Override
        public JsonNode persistedParameters() {
            return persistedParameters.deepCopy();
        }

        @Override
        public String toString() {
            return "Lease[proofToken=<redacted>, claimId=<redacted>, persistedParameters=<redacted>]";
        }
    }
}
