package com.cretas.aims.service.foodsafety.timelimit;

import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超时留痕写入。独立事务: 领用被拦时主事务要回滚, 这条记录必须留下来, 否则质检看不到待处置。
 * 单独成类: 同类内调用走不到事务代理。
 */
@Component
@RequiredArgsConstructor
public class ProcessTimeLimitEventWriter {

    private final ProcessTimeLimitEventRepository eventRepository;

    /** 同一对象已有待处置就不再写 (唯一索引 uq_ptle_open 兜并发)。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void openIfAbsent(ProcessTimeLimitEvent event) {
        boolean open = !eventRepository.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(
                event.getFactoryId(), event.getTargetType(), event.getTargetId(),
                ProcessTimeLimitEvent.Status.OPEN).isEmpty();
        if (!open) {
            eventRepository.save(event);
        }
    }
}
