package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.quality.FactoryRecallDTO;
import com.cretas.aims.service.quality.FactoryRecallService;
import com.cretas.aims.service.quality.FactoryRecallFollowUpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Factory scoped recall workflow; all writes use the authenticated request actor. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/quality-recalls")
@RequireModule("quality_inspection")
@RequiredArgsConstructor
public class FactoryRecallController {

    private final FactoryRecallService recallService;
    private final FactoryRecallFollowUpService followUpService;

    @GetMapping
    @RequirePermission({"quality:read", "quality:read_write"})
    public ResponseEntity<ApiResponse<List<FactoryRecallDTO.RecallSummary>>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) Long batchId) {
        return ResponseEntity.ok(ApiResponse.success(recallService.list(factoryId, batchId)));
    }

    @GetMapping("/preview")
    @RequirePermission({"quality:read", "quality:read_write"})
    public ResponseEntity<ApiResponse<FactoryRecallDTO.RecallView>> preview(
            @PathVariable String factoryId,
            @RequestParam Long batchId) {
        return ResponseEntity.ok(ApiResponse.success(recallService.preview(factoryId, batchId)));
    }

    @PostMapping
    @RequirePermission("quality:read_write")
    public ResponseEntity<ApiResponse<FactoryRecallDTO.RecallView>> create(
            @PathVariable String factoryId,
            @Valid @RequestBody FactoryRecallDTO.CreateRequest request,
            @RequestAttribute("userId") Long actorId) {
        return ResponseEntity.ok(ApiResponse.success(recallService.create(
                factoryId, request.getBatchId(), request.getReason(), actorId)));
    }

    @GetMapping("/{eventId}")
    @RequirePermission({"quality:read", "quality:read_write"})
    public ResponseEntity<ApiResponse<FactoryRecallDTO.RecallView>> get(
            @PathVariable String factoryId,
            @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.success(recallService.get(factoryId, eventId)));
    }

    @GetMapping("/{eventId}/follow-up")
    @RequirePermission({"quality:read", "quality:read_write"})
    public ResponseEntity<ApiResponse<FactoryRecallDTO.FollowUpView>> followUp(
            @PathVariable String factoryId,
            @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.success(followUpService.get(factoryId, eventId)));
    }

    @PostMapping("/{eventId}/customer-ack")
    @RequirePermission("quality:read_write")
    public ResponseEntity<ApiResponse<FactoryRecallDTO.RecallView>> customerAck(
            @PathVariable String factoryId,
            @PathVariable Long eventId,
            @Valid @RequestBody FactoryRecallDTO.CustomerAckRequest request,
            @RequestAttribute("userId") Long actorId) {
        return ResponseEntity.ok(ApiResponse.success(recallService.customerAck(
                factoryId, eventId, request.getShipmentId(), request.getNote(), actorId)));
    }

    @PostMapping("/{eventId}/returns")
    @RequirePermission("quality:read_write")
    public ResponseEntity<ApiResponse<FactoryRecallDTO.ReturnOrderSummary>> createReturn(
            @PathVariable String factoryId,
            @PathVariable Long eventId,
            @Valid @RequestBody FactoryRecallDTO.ReturnRequest request,
            @RequestAttribute("userId") Long actorId) {
        return ResponseEntity.ok(ApiResponse.success(recallService.createReturn(
                factoryId, eventId, request.getShipmentId(), request.getQuantity(),
                request.getReason(), actorId)));
    }

    @PostMapping("/{eventId}/close")
    @RequirePermission("quality:read_write")
    public ResponseEntity<ApiResponse<FactoryRecallDTO.RecallView>> close(
            @PathVariable String factoryId,
            @PathVariable Long eventId,
            @Valid @RequestBody FactoryRecallDTO.CloseRequest request,
            @RequestAttribute("userId") Long actorId) {
        return ResponseEntity.ok(ApiResponse.success(recallService.close(
                factoryId, eventId, request.getNote(), actorId)));
    }
}
