package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import java.util.Map;

/** Compatibility surface after restaurant-only tools were retired.
 * Every surviving tool must declare its access mode in its own class.
 */
public final class ToolAccessModeSeed {
    private ToolAccessModeSeed() {}
    public static final Map<String, AccessMode> PENDING_IN_CLASS_BACKFILL = Map.of();
}
