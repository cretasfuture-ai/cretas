# F006 Aurora runtime

F006's existing text-only Ollama caller is served by an isolated loopback protocol adapter. Each admitted request starts the official llama-server 0.40.6 executable against a fixed independently verified quantization of the original 27.3B checkpoint. The `qwen3.8:27b` tag never aliases a smaller model. The gateway converts native OpenAI SSE to the original Ollama NDJSON without changing or restarting shared Java/Python.

## Fixed native profile

Model blob SHA256 `b0d5696b72a61363c5fd3495c0c7034e8adc42020f65436175b6b57425c2be6d`, 9779561824 bytes. mmap loading, 24 GPU layers, lazy embedding reads, flash attention, q8_0 K/V caches, one slot, context10240, threads8, batch/microbatch64. Prompt RAM cache and context checkpoints are disabled. Warmup and CPU weight repacking are disabled to bound cold-load peaks. Native reasoning budget32 preserves genuine optional thinking; caller prediction budgets remain384/768. The native server and all descendants occupy a private process group and terminate after each request. Existing Ollama11434, VMs and other applications are untouched.

## Admission and protection

The lower GPU allocation uses production admission normal memory pressure, memory_pressure free percentage >=50, disk >=110 GiB, and no xcodebuild or CI Runner.Worker. Guest-query failures refuse admission without starting a guest. During initial loading and inference, independent watchdogs require normal pressure, free percentage >=15, disk >=105 GiB and swap growth <=64 MiB. On warning pressure2 with all other bounds healthy, only the owned model process group is paused for a total recovery budget of2 seconds. It resumes only after pressure1 returns; persistent warning, critical pressure, low space, swap growth or probe failure aborts it. The50-second wall deadline is never extended. New CI/native work refuses or interrupts only the owned model request. A separate50-second wall timer handles a slow upstream without newlines. The original caller keeps its55-second deadline and no cloud fallback. Cleanup never kills an unknown listener or process group; Darwin EPERM is ignored only after a bounded0.5-second ps grace proves the owned group has no live member; actually live inaccessible groups still fail.

The original system prompt must match exactly. Fixed additional guidance keeps incident responses concise, prioritizes isolation and traceability, and distinguishes physical from temporal separation. The original2000-character user limit is retained without truncation. Native SSE must carry the correct model, a single text-only choice, genuine optional reasoning, stop finish reason, consistent integer usage and `[DONE]`; only then can a terminal NDJSON frame be published. Invalid, truncated or incomplete streams fail closed.

## Installed layout

- `~/.local/lib/cretas-f006-aurora/f006_aurora_runtime.py`
- `~/.local/share/cretas-f006-aurora/{models,runtime-0.40.6,state}`
- `~/Library/LaunchAgents/com.cretas.f006-aurora-runtime.plist`
- `~/Library/LaunchAgents/com.cretas.f006-aurora-tunnel.plist`
- Gateway127.0.0.1:11438; private engine127.0.0.1:11437

Logs exclude prompts and answers: runtime.log512KiB with two backups. latest.json is atomically replaced. Child and launchd stdout/stderr go to /dev/null. Accepted HTTP connections have a separate absolute5-second parsing deadline, including incomplete headers and slow request bodies. Shutdown refuses new admission and drains the active bounded request. Plist loading, model acceptance, original-provider integration and production transport cutover are separate gates.

## Verification and cutover

`bash scripts/tests/test-f006-aurora-runtime.sh` runs isolated unit/HTTP/SSE/resource/owned-process fixtures, including resource failure during initial loading, slow streams, lock conflict, malformed model/usage/terminal, disk/report failures and bounded logs. Fixtures do not call a real model, SSH, VM management or production databases.

The chosen native profile additionally requires real Aurora resource and response testing, then the unchanged production provider through the final gateway and a temporary server-loopback transport. The former48-layer warm-cache experiment is historical and cannot prove cold-load admission. The final24-layer engine profile, unchanged provider and repeated cold-load transport must all pass independently before cutover. The lower initial free-percentage gate is coupled to fewer GPU layers; normal pressure, swap, low-space and live-build protection remain unchanged.

After normal PR checks and exact-main installation, hold the existing production F006 SQLite host lease in the same private/tmp namespace to wait for an active answer and make new requests temporarily MINI_BUSY. With the lease held, stop only the authorized Canada F006 tunnel, bind the Aurora restricted identity to server loopback11435, and verify health/ownership before releasing. A failed switch restores the original Canada tunnel and verifies the original model identity before release. Preserve source credentials and recovery archives. Do not restart shared Java/Python or test real withdrawals, payments, OTP, uploads or business writes. Whole-host shutdown remains separately prohibited.
