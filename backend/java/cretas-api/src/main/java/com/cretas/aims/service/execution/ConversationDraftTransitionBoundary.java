package com.cretas.aims.service.execution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

/** A short transaction over existing conversation and preview rows; contains no task state. */
@Service
public class ConversationDraftTransitionBoundary {
    @Transactional
    public <T> T run(Supplier<T> transition) {
        return transition.get();
    }
}
