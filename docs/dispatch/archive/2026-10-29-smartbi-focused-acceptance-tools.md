# SC29-FOCUSED-WEB-QA

Root; Base `d75971a8f747056859e35e7dcc7e1cc36da13731`; review, scope released on merge. Business acceptance remains under `SMARTBI-F006-QA-20261028`.

Adds opt-in canonical read-only scenarios for commerce AI and uploaded cost KPI readback. Both reject non-F006 configuration before opening a business page. Commerce checks two exact families, visible and candidate-bound facts, independent expected quantities by the selected physical unit, CNY channel amount, chart canvases, opaque-context original-detail requests/responses and a retained Excel download. The public API does not expose a provider/model: `answered` is evidence through the tested server contract that requires an AI planner result and fresh authorized candidates, not proof of a particular vendor.

Legacy acceptance selects the real upload-history batch, checks its visible filename and active cost sheet, observes the exact cost upload's analysis-cache GET and compares visible full amounts. It neither uploads data nor forces an analysis refresh. The pre-existing mutation guard and product code are unchanged.

Catalogue evidence now includes flat per-family status strings that survive depth redaction. All click steps have explicit bounded waits. The original long matrix run reached 25 retained Excel files but stalled at OEE details and produced no final report after the client timed out; that run remains TOOL_ERROR, not PASS. Subsequent acceptance uses smaller batches.

Validation: 85 related Node tests passed, including deterministic MCP bundle, registry and family guards. Five local Playwright DOM/asynchronous fixtures passed, covering missing families, wrong values, source mismatch and same-value wrong-upload rejection. Final F006 configuration guard is covered by Node tests. Original tool defects, intermediate fixtures and their corrections remain in private evidence. No production browser result is claimed by this source validation.
