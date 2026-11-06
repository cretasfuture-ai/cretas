package com.cretas.aims.repository.ai;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ToolExecutionIdempotencyRepository
        extends JpaRepository<ToolExecutionIdempotencyRecord, String> {

    Optional<ToolExecutionIdempotencyRecord>
    findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
            String tenantId,
            PrincipalType principalType,
            String principalId,
            String toolName,
            String descriptorVersion,
            String idempotencyKeyHash);

    @Query("SELECT r FROM ToolExecutionIdempotencyRecord r WHERE "
            + "r.tenantId = :tenantId AND r.businessType = :businessType "
            + "AND r.principalType = :principalType AND r.principalId = :principalId "
            + "AND r.toolName = :toolName AND r.descriptorVersion = :descriptorVersion "
            + "AND r.idempotencyKeyHash = :idempotencyKeyHash "
            + "AND r.commandDigest = :commandDigest "
            + "AND r.confirmationFingerprint = :confirmationFingerprint")
    Optional<ToolExecutionIdempotencyRecord> findByFullProductCreateLocator(
            @Param("tenantId") String tenantId,
            @Param("businessType") String businessType,
            @Param("principalType") com.cretas.aims.ai.tool.gateway.PrincipalType principalType,
            @Param("principalId") String principalId,
            @Param("toolName") String toolName,
            @Param("descriptorVersion") String descriptorVersion,
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("commandDigest") String commandDigest,
            @Param("confirmationFingerprint") String confirmationFingerprint);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ToolExecutionIdempotencyRecord r SET "
            + "r.resultEntityType = :entityType, "
            + "r.resultEntityId = :entityId, "
            + "r.resultReferenceStage = :stage "
            + "WHERE r.tenantId = :tenantId AND r.businessType = :businessType "
            + "AND r.principalType = :principalType AND r.principalId = :principalId "
            + "AND r.toolName = :toolName AND r.descriptorVersion = :descriptorVersion "
            + "AND r.idempotencyKeyHash = :idempotencyKeyHash "
            + "AND r.commandDigest = :commandDigest "
            + "AND r.confirmationFingerprint = :confirmationFingerprint "
            + "AND r.state = :expectedState "
            + "AND r.resultEntityId IS NULL AND r.resultReferenceStage IS NULL")
    int attachProductCreateReference(
            @Param("tenantId") String tenantId,
            @Param("businessType") String businessType,
            @Param("principalType") com.cretas.aims.ai.tool.gateway.PrincipalType principalType,
            @Param("principalId") String principalId,
            @Param("toolName") String toolName,
            @Param("descriptorVersion") String descriptorVersion,
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("commandDigest") String commandDigest,
            @Param("confirmationFingerprint") String confirmationFingerprint,
            @Param("expectedState") ToolExecutionIdempotencyRecord.State expectedState,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("stage") String stage);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ToolExecutionIdempotencyRecord r SET "
            + "r.resultReferenceStage = :nextStage "
            + "WHERE r.id = :recordId AND r.tenantId = :tenantId AND r.businessType = :businessType "
            + "AND r.principalType = :principalType AND r.principalId = :principalId "
            + "AND r.toolName = :toolName AND r.descriptorVersion = :descriptorVersion "
            + "AND r.idempotencyKeyHash = :idempotencyKeyHash "
            + "AND r.commandDigest = :commandDigest "
            + "AND r.confirmationFingerprint = :confirmationFingerprint "
            + "AND r.state = :expectedState "
            + "AND r.resultEntityType = :entityType AND r.resultEntityId = :entityId "
            + "AND r.resultReferenceStage = :expectedStage")
    int advanceProductCreateReference(
            @Param("recordId") String recordId,
            @Param("tenantId") String tenantId,
            @Param("businessType") String businessType,
            @Param("principalType") com.cretas.aims.ai.tool.gateway.PrincipalType principalType,
            @Param("principalId") String principalId,
            @Param("toolName") String toolName,
            @Param("descriptorVersion") String descriptorVersion,
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("commandDigest") String commandDigest,
            @Param("confirmationFingerprint") String confirmationFingerprint,
            @Param("expectedState") ToolExecutionIdempotencyRecord.State expectedState,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("expectedStage") String expectedStage,
            @Param("nextStage") String nextStage);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ToolExecutionIdempotencyRecord r SET r.state = :nextState, "
            + "r.outcomeStatus = :outcomeStatus, r.resultCode = :resultCode, "
            + "r.updatedAt = :now, r.completedAt = :now "
            + "WHERE r.id = :id AND r.state = :expectedState")
    int completeFromState(
            @Param("id") String id,
            @Param("expectedState") ToolExecutionIdempotencyRecord.State expectedState,
            @Param("nextState") ToolExecutionIdempotencyRecord.State nextState,
            @Param("outcomeStatus") ToolExecutionStatus outcomeStatus,
            @Param("resultCode") String resultCode,
            @Param("now") LocalDateTime now);
}
