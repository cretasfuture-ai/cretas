package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

/** Evaluation-only: print name / description / parameter schema of the given Tool classes (no Spring, no DB). */
public final class ToolSchemaDump {
    private ToolSchemaDump() {
    }

    public static void main(String[] classes) throws Exception {
        ObjectMapper om = new ObjectMapper();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String cls : classes) {
            Constructor<?> c = Class.forName(cls).getDeclaredConstructors()[0];
            c.setAccessible(true);
            ToolExecutor tool = (ToolExecutor) c.newInstance(new Object[c.getParameterCount()]);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("class", cls);
            one.put("description", tool.getDescription());
            one.put("schema", tool.getParametersSchema());
            out.put(tool.getToolName(), one);
        }
        System.out.println(om.writeValueAsString(out));
    }
}
