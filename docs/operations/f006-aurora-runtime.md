# F006 Aurora runtime

The loopback supervisor provides only the existing F006 text-only Ollama contract. It starts a separate Ollama process on demand, uses a private model store and Ollama 0.40.6 directory, and unloads/terminates its own process group after each request. Existing port 11434, CI services and other applications are untouched. Model tag `qwen3.8:27b` is reserved for an independently verified quantization of the original 27B checkpoint; it must never alias an 8B model.

## Gates

Before admission: normal memory pressure, memory_pressure free percentage >=60, disk >=110 GiB, no xcodebuild or CI Runner.Worker. Guest query errors refuse admission and do not start a guest. During inference: normal pressure, free percentage >=15, disk >=105 GiB and swap growth <=64 MiB. Every five seconds the build gate is checked again. A new build or resource breach stops only the private model process group. A separate 50-second wall timer works even when the upstream sends no newline. The original caller retains its 55-second timeout and no cloud fallback.

Only fixed text messages, model, thinking mode and original prediction budgets are accepted. Context is explicitly 10240, thread count4, keep_alive0, one concurrent request. llama.cpp prompt RAM cache and context checkpoints are disabled to prevent hidden checkpoint growth. The incoming system prompt must exactly match the production provider; the private runtime adds fixed concise-answer, isolation/traceability and cross-contamination guidance before inference. User questions keep the original2000-character limit. This does not change the production caller file or invent live data. Terminal frames require nonempty text, stop reason, consistent model and integer usage. Runtime logs exclude prompts and answers: 512 KiB with two backups; latest.json replaces its predecessor atomically. Child logs go to /dev/null. launchd stdout/stderr go to /dev/null.

## Installed layout

- ~/.local/lib/cretas-f006-aurora/f006_aurora_runtime.py
- ~/.local/share/cretas-f006-aurora/{models,runtime-0.40.6,state}
- ~/Library/LaunchAgents/com.cretas.f006-aurora-runtime.plist
- gateway 127.0.0.1:11438, isolated model127.0.0.1:11437

The plist may be loaded only after the chosen model passes resource, response quality and actual caller protocol acceptance. Loading an idle gateway is separate from production tunnel cutover. Inspect existing labels/ports and active request state before any replacement. Shutdown refuses new admission and waits for the active bounded request; do not kill a running business request.

## Cutover boundary

No Java/Python application restart is needed when the truthful 27B tag remains compatible. Hold the existing production f006_mini_lease.acquire_host_lease() in the same private /tmp namespace before changing port11435; acquisition waits for in-flight completion and temporarily makes new requests MINI_BUSY. Keep the gate held through the tunnel switch and listener checks, then release. Canada source jobs and original credentials stay recoverable. SSH authorization changes and Canada root LaunchDaemon stop require the user's separately bounded approval. Never copy the old root private key.

A green unit suite is not model quality, production cutover or whole-host retirement acceptance. Keep their receipts separate. No real withdrawals, user prompts, payment operations or real auth submissions belong in tests.

## Verification

`bash scripts/tests/test-f006-aurora-runtime.sh` runs isolated fixtures, including slow no-newline streams, failure cleanup and actual owned child process cleanup. No real model, SSH, VM changes or production database is used by this suite.
