package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** A narrow quality capability, separate from full inventory reads used by inventory AI tools. */
@Service
@RequiredArgsConstructor
public class DisposalMaterialBatchOptionsService {
    private final MaterialBatchService materialBatchService;

    @Transactional(readOnly = true)
    public PageResponse<MaterialBatchOption> list(String factoryId, int page, int size, String keyword) {
        PageRequest request = PageRequest.of(page, size, "createdAt", "DESC");
        request.setStatus(MaterialBatchStatus.AVAILABLE.name());
        request.setKeyword(keyword);
        // The existing external Spring proxy retains its DataScope and SQL-side filtering.
        PageResponse<MaterialBatchDTO> result = materialBatchService.getMaterialBatchList(factoryId, request);
        var options = result.getContent().stream().map(batch -> project(factoryId, batch)).toList();
        return PageResponse.of(options, result.getPage(), result.getSize(), result.getTotalElements());
    }

    private static MaterialBatchOption project(String factoryId, MaterialBatchDTO batch) {
        if (!factoryId.equals(batch.getFactoryId()) || batch.getStatus() != MaterialBatchStatus.AVAILABLE) {
            throw new BusinessException(403, "原料候选批次范围不一致，请刷新后重试");
        }
        return new MaterialBatchOption(batch.getId(), batch.getBatchNumber(), batch.getMaterialTypeId(),
                batch.getMaterialName(), batch.getCurrentQuantity(), batch.getQuantityUnit());
    }

    public record MaterialBatchOption(String id, String batchNumber, String materialTypeId,
                                      String materialName, BigDecimal currentQuantity, String quantityUnit) {}
}
