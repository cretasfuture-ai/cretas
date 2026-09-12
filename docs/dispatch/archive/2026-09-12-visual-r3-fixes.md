# FIX-VISUAL-R3-20260912

Base: `9fdcdbce2e285ab174a3afa8c13a55e97c588ccf`. Owner: current coordinator. Candidate review; merge and deployment are recorded separately in the release receipt.

Scope: WorkspaceTabs / WorkspaceReferencePane / AppLayout focus and narrow reference layout, YieldCardTable header, PendingQueue fixed-column background, targeted tests. No business data, permissions, backend or other active owner scope changed.

Bug fixes: native sibling close buttons support Space/Enter and restore active-tab focus; reference close restores focus; narrow reference panels stack instead of squeezing the primary form; synchronization reads the independently scrolling primary panel; full sequence header and transparent borderless button style; opaque themed urgent/overdue row backgrounds prevent underlying text bleeding through the frozen action cell.

Local real Vue browser evidence: `C:/Users/Steve/cretas-visual-fixes-20260912-r3`. All API responses are local fixtures and external origins are blocked. Space closes 2 tabs to 1; focus returns to a button. At 1024, primary width grows from 450.25px to 804px; independent reference ArrowDown scrolls 40px; synchronization at 50% reaches 50%; 1440 remains side by side; draft date 2030-01-15 survives. Yield header is 序号, 80px despite saved legacy 58px, border 0, ascending and descending sorting verified. Urgent fixed action cell is opaque without increasing its 100px width. Screenshots inspected by coordinator.

Target tests: 7 files / 48 tests passed, covering keyboard/focus, workspace ordering/cache, column width persistence/tenant isolation, yield behavior and queue counts. Type checking and release build results belong to the final local logs and manifest; no claim of CI or production acceptance is made here.

Review: only registered scope; no unrelated changes, no weakened tests or permissions. Updated default-width assertion is backed by a new old-saved-width regression case. Tradeoff: narrow mode shows fewer vertical rows but preserves both panels and independent scrolling. No measured throughput claim. Native touch, OS DPI, all queue states, full business persistence and the workbook's larger multi-instance/global action-template requirements remain unverified or unfinished.
