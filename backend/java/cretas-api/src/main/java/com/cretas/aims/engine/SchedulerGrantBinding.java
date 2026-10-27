package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * The one computation of what a scheduler config's write run is bound to. The grant endpoint
 * issues against it and the scheduler consumes against it at fire time, so the two cannot drift.
 */
public final class SchedulerGrantBinding {

    private SchedulerGrantBinding() {
    }

    public static AutonomousGrantService.Binding of(FactorySchedulerConfig config, String descriptorVersion,
            ObjectMapper objectMapper) {
        Map<String, Object> params = config.getParams() == null ? Map.of() : config.getParams();
        String digest = ToolCommandDigest.parametersHash(objectMapper.valueToTree(params));
        return new AutonomousGrantService.Binding(config.getFactoryId(), ToolExecutionSource.SCHEDULER.name(),
                String.valueOf(config.getId()), config.getToolOrMethod(), config.getRunAsUserId(), digest,
                AutonomousGrantService.configRevision(config.getTaskCode(), config.getCronExpression(),
                        config.getToolOrMethod(), digest, config.getRunAsUserId()),
                descriptorVersion);
    }
}
