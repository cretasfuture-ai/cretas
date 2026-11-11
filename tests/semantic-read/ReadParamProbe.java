package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.StatusFilter;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Evaluation-only replay of main's deterministic read-parameter binding (INTENT-DISTILL line E).
 *
 * <p>Runs against compiled main classes; no Spring context, no database, no model. Per turn it
 * binds page/size from {@link ReadTurnParameters#pageWords}, status from
 * {@link StatusFilter#fromSentence} over the target tool's own schema, then inherits the
 * previous read on the same target with {@link ReadTurnParameters#inherit}. A server-side page
 * (PAGINATION_* intent, or a PAGE dialogue act from the decision model when the sentence names no page)
 * replays the previous read with page ±1, the D1 server-page rule. A turn that did not execute a
 * read clears the snapshot, as {@code ReadTurnParameters.latest} only sees the latest assistant reply.
 *
 * <p>stdin: JSON lines {case, intent, sentence, act, outcome}; stdout: JSON lines {params, sources, note}.
 */
public final class ReadParamProbe {

    private static final Map<String, String> TOOL_CLASSES = Map.of(
            "ORDER_LIST", "com.cretas.aims.ai.tool.impl.crm.OrderListTool",
            "ORDER_STATUS", "com.cretas.aims.ai.tool.impl.crm.OrderQueryTool",
            "MATERIAL_BATCH_QUERY", "com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool",
            "MATERIAL_EXPIRED_QUERY", "com.cretas.aims.ai.tool.impl.material.MaterialExpiredQueryTool");

    private ReadParamProbe() {
    }

    static ToolExecutor tool(String intent) throws Exception {
        String cls = TOOL_CLASSES.get(intent);
        if (cls == null) return null;
        Constructor<?> c = Class.forName(cls).getDeclaredConstructors()[0];
        c.setAccessible(true);
        Object[] args = new Object[c.getParameterCount()];
        return (ToolExecutor) c.newInstance(args);
    }

    public static void main(String[] a) throws Exception {
        ObjectMapper om = new ObjectMapper();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Map<String, ReadTurnParameters.Snapshot> last = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> req = om.readValue(line, Map.class);
            String caseId = String.valueOf(req.get("case"));
            String intent = (String) req.get("intent");
            String sentence = (String) req.get("sentence");
            String act = (String) req.get("act");
            String outcome = (String) req.get("outcome");
            Map<String, Object> out = new LinkedHashMap<>();
            ReadTurnParameters.Snapshot prev = last.get(caseId);
            if (!"EXECUTE".equals(outcome) || intent == null) {
                last.remove(caseId);
                out.put("note", "not_executed");
                System.out.println(om.writeValueAsString(out));
                continue;
            }
            boolean pagination = ReadPaginationSupport.isPaginationIntent(intent);
            String target = pagination && prev != null ? prev.intentCode() : intent;
            ToolExecutor tool = tool(target);
            if (tool == null) {
                last.remove(caseId);
                out.put("note", "no_read_tool:" + target);
                System.out.println(om.writeValueAsString(out));
                continue;
            }
            Map<String, Object> schema = tool.getParametersSchema();
            Map<?, ?> props = ReadTurnParameters.properties(schema);
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, ReadTurnParameters.Source> sources = new LinkedHashMap<>();
            ReadTurnParameters.PageWords pw = ReadTurnParameters.pageWords(sentence);
            String pageKey = ReadTurnParameters.pageKey(props);
            String sizeKey = ReadTurnParameters.sizeKey(props);
            if (pw.page() != null && pageKey != null) {
                params.put(pageKey, pw.page());
                sources.put(pageKey, ReadTurnParameters.Source.CURRENT_TURN);
            }
            if (pw.size() != null && sizeKey != null) {
                params.put(sizeKey, pw.size());
                sources.put(sizeKey, ReadTurnParameters.Source.CURRENT_TURN);
            }
            Map<String, Object> statusProp = StatusFilter.statusProperty(schema);
            if (statusProp != null) {
                try {
                    Optional<String> st = StatusFilter.fromSentence(statusProp, sentence);
                    if (st.isPresent()) {
                        params.put("status", st.get());
                        sources.put("status", ReadTurnParameters.Source.CURRENT_TURN);
                    }
                } catch (RuntimeException refused) {
                    out.put("status_refused", refused.getMessage());
                }
            }
            boolean serverPage = prev != null && prev.intentCode().equals(target) && pageKey != null
                    && (pagination || ("PAGE".equals(act) && pw.page() == null));
            if (serverPage) {
                boolean previous = ReadPaginationSupport.isPreviousIntent(intent);
                Object pp = prev.params().get(pageKey);
                int prevPage = pp instanceof Number n ? n.intValue() : 1;
                for (Map.Entry<String, Object> e : prev.params().entrySet()) {
                    if (!params.containsKey(e.getKey())) {
                        params.put(e.getKey(), e.getValue());
                        sources.put(e.getKey(), ReadTurnParameters.Source.INHERITED);
                    }
                }
                params.put(pageKey, Math.max(1, prevPage + (previous ? -1 : 1)));
                sources.put(pageKey, ReadTurnParameters.Source.SERVER_PAGE);
            } else {
                ReadTurnParameters.inherit(params, sources, props, prev, target, tool.getToolName(), Set.of());
            }
            if (pageKey != null && !params.containsKey(pageKey)) {
                params.put(pageKey, 1);
                sources.put(pageKey, ReadTurnParameters.Source.DEFAULT);
            }
            last.put(caseId, new ReadTurnParameters.Snapshot(target, tool.getToolName(), new LinkedHashMap<>(params)));
            Map<String, Object> norm = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String k = e.getKey().equals(pageKey) ? "page" : e.getKey().equals(sizeKey) ? "size" : e.getKey();
                norm.put(k, e.getValue());
            }
            out.put("target", target);
            out.put("params", norm);
            out.put("sources", sources);
            System.out.println(om.writeValueAsString(out));
        }
    }
}
