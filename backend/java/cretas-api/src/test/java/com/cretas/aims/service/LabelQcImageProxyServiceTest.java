package com.cretas.aims.service;

import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.LabelQcPhoto;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LabelQcPhotoRepository;
import com.cretas.aims.service.attachment.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabelQcImageProxyServiceTest {

    @Mock private LabelQcPhotoRepository photoRepository;
    @Mock private AttachmentService attachmentService;
    private LabelQcImageProxyService service;

    @BeforeEach
    void setUp() {
        service = new LabelQcImageProxyService(photoRepository, attachmentService);
        LabelQcPhoto photo = LabelQcPhoto.builder()
                .id("photo-1")
                .factoryId("LIUSHANMEN")
                .attachmentId("attachment-1")
                .build();
        when(photoRepository.findByFactoryIdAndId("LIUSHANMEN", "photo-1"))
                .thenReturn(Optional.of(photo));
    }

    @Test
    void rejectsNonImageAttachmentBeforeOpeningAnyRemoteUrl() {
        Attachment attachment = Attachment.builder()
                .id("attachment-1")
                .factoryId("LIUSHANMEN")
                .fileType("application/pdf")
                .fileSize(1024L)
                .fileUrl("ignored")
                .build();
        when(attachmentService.getById("LIUSHANMEN", "attachment-1"))
                .thenReturn(attachment);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.load("LIUSHANMEN", "photo-1"));

        assertEquals("LABEL_QC_IMAGE_TYPE_INVALID", error.getErrorCode());
        verify(attachmentService, never()).generateDownloadUrl("LIUSHANMEN", "attachment-1");
    }

    @Test
    void rejectsOversizedImageBeforeOpeningAnyRemoteUrl() {
        Attachment attachment = Attachment.builder()
                .id("attachment-1")
                .factoryId("LIUSHANMEN")
                .fileType("image/jpeg")
                .fileSize(21L * 1024 * 1024)
                .fileUrl("ignored")
                .build();
        when(attachmentService.getById("LIUSHANMEN", "attachment-1"))
                .thenReturn(attachment);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.load("LIUSHANMEN", "photo-1"));

        assertEquals("LABEL_QC_IMAGE_TOO_LARGE", error.getErrorCode());
        verify(attachmentService, never()).generateDownloadUrl("LIUSHANMEN", "attachment-1");
    }
}
