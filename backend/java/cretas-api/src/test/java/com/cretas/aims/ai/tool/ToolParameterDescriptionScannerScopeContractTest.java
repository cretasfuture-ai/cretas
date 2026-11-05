package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.impl.crm.CustomerCrmStatusQueryTool;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolParameterDescriptionScannerScopeContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void actualScannerKeepsProductionToolsAndRejectsTestOutputFixtures() throws Exception {
        var scanner = new ToolParameterDescriptionLeakScanTest();
        var method = ToolParameterDescriptionLeakScanTest.class.getDeclaredMethod("toolClasses");
        method.setAccessible(true);
        List<Class<?>> tools = (List<Class<?>>) method.invoke(scanner);
        assertThat(tools).contains(UserDisableTool.class, CustomerCrmStatusQueryTool.class)
                .doesNotContain(SchemaFixtureTool.class);
        assertThat(tools).hasSizeGreaterThan(400);
        assertThat(new SchemaFixtureTool().getParametersSchema().toString())
                .as("positive control: the excluded fixture really contains a dirty description")
                .contains("UUID");
    }

    public static final class SchemaFixtureTool extends AbstractBusinessTool {
        @Override public String getToolName() { return "schema_scanner_fixture"; }
        @Override public String getDescription() { return "scanner fixture, never a production bean"; }
        @Override public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties",
                    Map.of("id", Map.of("type", "string", "description", "fixture UUID")),
                    "required", List.of("id"));
        }
        @Override protected List<String> getRequiredParameters() { return List.of("id"); }
        @Override protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                                         Map<String, Object> context) {
            throw new AssertionError("the schema scanner must never execute a tool");
        }
    }
}
