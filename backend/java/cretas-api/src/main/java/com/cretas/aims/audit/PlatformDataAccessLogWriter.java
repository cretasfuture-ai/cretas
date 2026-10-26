package com.cretas.aims.audit;

import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import com.cretas.aims.repository.audit.PlatformDataAccessLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Persists platform data-access rows off the request thread.
 *
 * <p>Two failure points, both logged at ERROR with the full row content so that a record is
 * never dropped without a trace: the bounded queue rejecting the task
 * ({@code platform_data_access_log_enqueue_failed}) and the database write itself
 * ({@code platform_data_access_log_write_failed}). Neither ever propagates to the request.
 */
@Slf4j
@Component
public class PlatformDataAccessLogWriter {

    static final String ENQUEUE_FAILED = "platform_data_access_log_enqueue_failed";
    static final String WRITE_FAILED = "platform_data_access_log_write_failed";

    private final PlatformDataAccessLogRepository repository;
    private final Executor executor;

    public PlatformDataAccessLogWriter(PlatformDataAccessLogRepository repository,
                                       @Qualifier(PlatformDataAccessLogAsyncConfiguration.EXECUTOR) Executor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    public void submit(PlatformDataAccessLog row) {
        try {
            executor.execute(() -> write(row));
        } catch (Throwable rejected) {
            log.error("{} errorType={} {}", ENQUEUE_FAILED, rejected.getClass().getSimpleName(), describe(row));
        }
    }

    void write(PlatformDataAccessLog row) {
        try {
            repository.save(row);
        } catch (Throwable failure) {
            log.error("{} errorType={} {}", WRITE_FAILED, failure.getClass().getSimpleName(), describe(row));
        }
    }

    static String describe(PlatformDataAccessLog row) {
        if (row == null) return "row=null";
        return "target_factory=" + row.getTargetFactoryId()
                + " target_source=" + row.getTargetSource()
                + " actor_type=" + row.getActorType()
                + " actor_user_id=" + row.getActorUserId()
                + " actor_username=" + row.getActorUsername()
                + " actor_role=" + row.getActorRole()
                + " method=" + row.getHttpMethod()
                + " path=" + row.getRequestPath()
                + " query_params=" + row.getQueryParamNames()
                + " status=" + row.getResponseStatus()
                + " client_ip=" + row.getClientIp()
                + " at=" + row.getCreatedAt()
                + " correlation=" + row.getCorrelationId();
    }
}
