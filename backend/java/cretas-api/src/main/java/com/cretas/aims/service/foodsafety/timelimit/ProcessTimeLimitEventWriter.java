package com.cretas.aims.service.foodsafety.timelimit;

import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent.RecordKind;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 超时留痕写入。独立事务: 领用被拦时主事务要回滚, 这条记录必须留下来, 否则质检看不到待处置。
 * 单独成类: 同类内调用走不到事务代理。
 */
@Component
@RequiredArgsConstructor
public class ProcessTimeLimitEventWriter {

    private final ProcessTimeLimitEventRepository eventRepository;

    /**
     * 已有同一格的待处置就不再写 (唯一索引 uq_ptle_open / uq_ptle_open_start_missing 兜并发):
     * <ul>
     *   <li>超时记录 (被拦 / 只警告): 同一对象一条, 不分工序 (原行为);</li>
     *   <li>未登记解冻的复盘记录: 同一对象 + 同一工序端口一条, 不占超时那一格 (PROTO-Q41)。</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void openIfAbsent(ProcessTimeLimitEvent event) {
        boolean startMissing = RecordKind.WARN_START_MISSING.equals(event.getRecordKind());
        boolean open = eventRepository.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(
                        event.getFactoryId(), event.getTargetType(), event.getTargetId(),
                        ProcessTimeLimitEvent.Status.OPEN).stream()
                .anyMatch(existing -> startMissing
                        ? RecordKind.WARN_START_MISSING.equals(existing.getRecordKind())
                                && Objects.equals(existing.getWorkflowPortId(), event.getWorkflowPortId())
                        : !RecordKind.WARN_START_MISSING.equals(existing.getRecordKind()));
        if (!open) {
            eventRepository.save(event);
        }
    }
}
