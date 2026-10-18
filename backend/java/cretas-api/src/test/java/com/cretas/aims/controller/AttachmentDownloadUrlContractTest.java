package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.security.AttachmentPermissionResolver;
import com.cretas.aims.service.attachment.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AttachmentDownloadUrlContractTest {
    private final AttachmentService service = mock(AttachmentService.class);
    private final AttachmentPermissionResolver permissions = mock(AttachmentPermissionResolver.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AttachmentController(service, permissions, mock(AttachmentRepository.class))).build();
    }
    private void attachment() {
        Attachment attachment = new Attachment();
        attachment.setId("A1"); attachment.setFactoryId("TEST-A");
        attachment.setEntityType(Attachment.EntityType.SHIPMENT);
        when(service.getById("TEST-A", "A1")).thenReturn(attachment);
    }
    @Test void returnsAuthorizedSignedLinkAsJsonWithoutRedirect() throws Exception {
        attachment();
        when(service.generateDownloadUrl("TEST-A", "A1")).thenReturn("https://example.invalid/receipt?test-signature=sample");
        mvc.perform(get("/api/mobile/TEST-A/attachments/A1/download-url"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.url").value("https://example.invalid/receipt?test-signature=sample"));
        verify(permissions).requireRead(any(), eq(Attachment.EntityType.SHIPMENT));
    }
    @Test void deniedModuleCannotIssueSignedLink() throws Exception {
        attachment();
        when(service.generateDownloadUrl("TEST-A", "A1")).thenReturn("https://example.invalid/receipt");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(permissions).requireRead(any(), eq(Attachment.EntityType.SHIPMENT));
        mvc.perform(get("/api/mobile/TEST-A/attachments/A1/download-url")).andExpect(status().isForbidden());
        verify(service, never()).generateDownloadUrl(any(), any());
    }
    @Test void anotherFactoryCannotResolveAttachmentOrIssueLink() throws Exception {
        when(service.getById("TEST-B", "A1")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/mobile/TEST-B/attachments/A1/download-url")).andExpect(status().isNotFound());
        verify(service, never()).generateDownloadUrl(any(), any());
    }
    @Test void endpointKeepsTheExistingDownloadPermissionAnnotation() throws Exception {
        var old = AttachmentController.class.getMethod("download", String.class, String.class, HttpServletRequest.class);
        var added = java.util.Arrays.stream(AttachmentController.class.getMethods())
                .filter(m -> m.getName().equals("downloadUrl")).findFirst();
        assertThat(added).isPresent();
        assertThat(added.orElseThrow().getAnnotation(RequirePermission.class).value())
                .containsExactly(old.getAnnotation(RequirePermission.class).value());
    }
}
