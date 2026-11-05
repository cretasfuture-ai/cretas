package com.cretas.aims.service.attachment.dto;

import com.cretas.aims.entity.Attachment;
import com.cretas.aims.security.AuthenticatedActorType;

import java.time.LocalDateTime;

/** SOP-only metadata contract. Object locators are intentionally absent. */
public record SopAttachmentMetadata(
        String id,
        String factoryId,
        Attachment.EntityType entityType,
        String entityId,
        String fileName,
        Long fileSize,
        String fileType,
        Attachment.FileCategory fileCategory,
        String fileStorage,
        String fileHash,
        String businessTag,
        String description,
        LocalDateTime uploadedAt,
        UploadedActor uploadedByActor) {

    public record UploadedActor(AuthenticatedActorType type, Long id) { }

    public static SopAttachmentMetadata from(Attachment attachment) {
        UploadedActor actor = attachment.getUploadedActorType() == null || attachment.getUploadedActorId() == null
                ? null
                : new UploadedActor(attachment.getUploadedActorType(), attachment.getUploadedActorId());
        return new SopAttachmentMetadata(
                attachment.getId(), attachment.getFactoryId(), attachment.getEntityType(), attachment.getEntityId(),
                attachment.getFileName(), attachment.getFileSize(), attachment.getFileType(),
                attachment.getFileCategory(), attachment.getFileStorage(), attachment.getFileHash(),
                attachment.getBusinessTag(), attachment.getDescription(), attachment.getUploadedAt(), actor);
    }
}
