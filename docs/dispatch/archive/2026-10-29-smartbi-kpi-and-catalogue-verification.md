# SmartBI KPI and catalogue verification

Tasks: `SC29-KPI-THOUSANDS`, `SC29-CATALOG-LAZY-QA`, `SC29-COMMERCE-DETAIL-UI`.
Owner: Root. Status: review, PR #1292; product scope released on merge.

F006's normal Excel-upload page displayed formatted costs 2,500 and 1,600 as 2 and 1. The animation parsed a numeric prefix from a formatted string. Custom KPI values now retain their complete text; numeric animation and progress use strict finite parsing and invalid updates cannot leave the old number visible.

The Web original-details contract now uses the server's boolean `drilldown` capability. When a chart supports whole-chart details only, the Web client omits point filters and labels the resulting table honestly. The already published native client observes the same boolean contract.

Catalogue verification recognizes lazy snapshot-backed details, uses the existing guided preview action without saving selection preferences, waits for asynchronous topic controls and viewport-driven chart rendering, and keeps one distinct export per family. It still requires actual chart rendering, source/period/candidate-matched original rows and an export containing the selected candidate. The production mutation guard and its whitelist are unchanged.

Validation before main integration: 22 mounted KPI tests; 29 combined KPI/details/API component cases; full vue-tsc with 8GB; 168 canonical harness Node cases; eight-family browser fixture including duplicate labels, delayed controls, original details, separate exports and zero preference writes. Logs and source hashes are retained privately. Main integration may require the shared type/bootstrap gates again if their inputs change.

Live failed tool attempts are retained separately. The corrected single-family diagnostic passed selection, original details and export checks, but its pre-viewport rendering assertion remained UNVERIFIED; this is not an all-catalogue PASS. Final deployed F006 matrix, new upload KPI readback and native business acceptance remain under parent `SMARTBI-F006-QA-20261028`.

No credentials, customer rows or tokens are tracked here. Backend commerce joins and the release window have separate tasks and evidence.
