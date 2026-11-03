package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发布时把画布工序节点上的 {@code ccpPoints} 落成 CCP 台账行({@code haccp_checkpoints.work_process_id},
 * 缺口 13 的报工监控读的就是这张表)。
 *
 * <p>画布节点 data 是真值, 台账行是投影: 编码由 (产品, 节点, 控制点 id) 确定性生成, 重复发布是更新不是新增。
 * 只在 {@link WorkflowPendingConfirmations#blockers} 为空之后调用, 所以这里拿到的限值一定齐全。
 * 画布上删掉的控制点不动台账(已有监控记录的 CCP 不能悬空, 要停用走食品安全页)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowCcpMaterializer {

    private final HaccpCheckpointRepository repository;

    /** @return 写入(新建或更新)的台账行数 */
    public int materialize(String factoryId, String productTypeId, ProductProcessWorkflowDTO definition) {
        int written = 0;
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (!"PROCESS".equals(node.getKind()) || node.getData() == null
                    || !(node.getData().get(WorkflowPendingConfirmations.CCP_POINTS) instanceof List<?> points)) {
                continue;
            }
            Object workProcessId = node.getData().get("workProcessId");
            if (!(workProcessId instanceof String wp) || wp.isBlank()) continue;
            int index = 0;
            for (Object raw : points) {
                index++;
                if (!(raw instanceof Map<?, ?> point)) continue;
                String pointId = point.get("id") instanceof String s && !s.isBlank() ? s : "p" + index;
                String code = "WF-" + digest(productTypeId + "|" + node.getId() + "|" + pointId);
                Optional<HaccpCheckpoint> existing = repository.findByFactoryIdAndCheckpointCode(factoryId, code);
                HaccpCheckpoint row = existing.orElseGet(() -> HaccpCheckpoint.builder()
                        .factoryId(factoryId).checkpointCode(code).active(true).build());
                row.setName(text(point.get("name"), "控制点"));
                row.setHazardType(String.valueOf(point.get("hazardType")));
                row.setCriticalLimitMin(new BigDecimal(String.valueOf(point.get("criticalLimitMin"))));
                row.setCriticalLimitMax(new BigDecimal(String.valueOf(point.get("criticalLimitMax"))));
                row.setUnit(String.valueOf(point.get("unit")));
                row.setMonitoringProcedure(optional(point.get("monitoringProcedure")));
                row.setCorrectiveAction(optional(point.get("correctiveAction")));
                row.setDescription("由工艺画布发布生成(产品 " + productTypeId + ")");
                row.setWorkProcessId(wp);
                repository.save(row);
                written++;
            }
        }
        if (written > 0) log.info("workflow publish materialized {} CCP rows factory={} product={}",
                written, factoryId, productTypeId);
        return written;
    }

    private static String text(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s.trim() : fallback;
    }

    private static String optional(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String digest(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 5; i++) out.append(String.format("%02x", hash[i]));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
