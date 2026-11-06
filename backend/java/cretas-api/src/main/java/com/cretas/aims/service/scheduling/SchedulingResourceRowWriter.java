package com.cretas.aims.service.scheduling;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/** Keeps Excel's per-row partial-commit semantics while serializing resource facts. */
@Service
@RequiredArgsConstructor
public class SchedulingResourceRowWriter {

    private final SchedulingResourceWriteLock resourceWriteLock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T writeRow(String factoryId, Supplier<T> rowWrite) {
        resourceWriteLock.lockFactory(factoryId);
        return rowWrite.get();
    }
}
