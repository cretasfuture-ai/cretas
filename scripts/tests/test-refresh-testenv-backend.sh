#!/usr/bin/env bash
# scripts/deploy/refresh-testenv-backend.sh 的测试 —— 假 ssh/scp 桩, 不碰任何机器。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这个测试在守什么
# ═══════════════════════════════════════════════════════════════════════════
# 被测脚本把「手敲七步」变成一条命令; 手敲时最容易少的三步, 这里各有一条用例钉着:
#   · jar 落后 main 就**拒绝**, 且拒绝发生在**任何远端接触之前** (calls.log 为空是
#     那条阴性对照 —— 「拒绝了」和「拒绝了但已经传了 170MB」是两回事)
#   · 传输后两侧 sha 不一致 ⇒ rc=2 **且 install 步骤没被调** (装一个坏 jar 比不装糟)
#   · health 超时 ⇒ rc=1, 判决行带 backup= 路径 (回滚要用)
#
# 三态退出码的价值全在 rc=2 那一态, 而它平时不会自然发生 ⇒ **每种 rc=2 原因各构造一次**
# (硬约束 4)。最后 READING: RC2_REASONS_CONSTRUCTED= 报出构造到的种数。
#
# 「没发生」的对照 (硬约束 9): 每次运行台账**恰好长 1 行**, run_it 里断言。
# 台账没涨 = 脚本崩在写判决之前, 那时任何 rc 读数都无意义。
#
# ⚠️ 每条断言前先把判决行打出来 (形态 C″): 变异不红有两种相反的成因, 不看读数分不清。
#
# 变异实测 (2026-09-06, 先确认变异真的进了代码, 再看哪条红):
#   ① 去掉 `git diff --quiet … -- backend/java` 那道拒绝  ⇒ [2] 红 (不再 REFUSED, 走到远端后 rc=2)
#   ② 去掉传输后 `[ "$TMP_SHA" = "$JAR_SHA" ]` 的比对       ⇒ [3] 红 (install 被调了)
#   ③ 去掉 pid_before = pid_after 的判定                     ⇒ [12] 红 (判决行变成 REFRESHED)
#   ④ 远端循环改回「只看 200 就 break」                       ⇒ [C-replay] 红 (attempts 1, 旧 pid 的 200 被收下)
#   ⑤ 去掉 launchd_label≠ok 的 dead                          ⇒ [B1] 红 (scp 在标签不在时照传)
#   ⑥ 去掉 remote_jar_sha256=none 的 refuse                  ⇒ [A1] 红 (jar 不在照装)
#
# 2026-09-06 organizer 终审补的三条 (A/B/C), 每条各有一个桩用例 + 一次变异:
#   A 目录在 jar 不在 ⇒ REFUSED remote-jar-missing (除非 --allow-new-install); plist 不引用 $J ⇒ REFUSED
#   B launchd 标签不在 ⇒ 预检就 rc=2, 一个字节都不传; kickstart rc≠0 ⇒ rc=1
#   C health 200 必须与「同一轮读到的 pid ≠ pid_before」配对; 生成的 install.sh 在假 launchctl/curl
#     下真跑一遍 (旧进程先答 200、pid 随后才翻 —— 审查者构造的那个竞态)
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRIPT="$ROOT_DIR/scripts/deploy/refresh-testenv-backend.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$SCRIPT" ] || fail "$SCRIPT 不存在"
bash -n "$SCRIPT" || fail "语法错误: $SCRIPT"
for t in git sha256sum shasum; do command -v $t >/dev/null 2>&1 && HAVE_SHA=1; done
command -v git >/dev/null 2>&1 || fail "本机缺 git"
sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

# ── git 夹具: 本地 bare origin, main 上两个 commit, jar 可以「等于」或「落后」它 ──
REPO="$TMP/repo"; ORIGIN="$TMP/origin.git"
git init -q "$REPO" && git -C "$REPO" config user.email t@t && git -C "$REPO" config user.name t \
    && git -C "$REPO" config commit.gpgsign false || fail "git init"
mkdir -p "$REPO/backend/java/cretas-api" "$REPO/web-admin"
echo a > "$REPO/backend/java/cretas-api/A.java"; echo w > "$REPO/web-admin/w.ts"
git -C "$REPO" add -A && git -C "$REPO" commit -qm A || fail "commit A"
A_SHA=$(git -C "$REPO" rev-parse HEAD)
git init -q --bare "$ORIGIN" && git -C "$REPO" remote add origin "$ORIGIN" \
    && git -C "$REPO" push -q origin HEAD:main && git -C "$REPO" fetch -q origin || fail "origin"
echo b > "$REPO/backend/java/cretas-api/B.java"
git -C "$REPO" add -A && git -C "$REPO" commit -qm B && git -C "$REPO" push -q origin HEAD:main \
    && git -C "$REPO" fetch -q origin || fail "commit B"
B_SHA=$(git -C "$REPO" rev-parse HEAD)
# C 只改 web-admin: Java 树与 B 相同 ⇒ 用 B 的 jar 刷 C 的 main 必须**放行** (squash 合并常态)
echo w2 > "$REPO/web-admin/w.ts"
git -C "$REPO" add -A && git -C "$REPO" commit -qm C && git -C "$REPO" push -q origin HEAD:main \
    && git -C "$REPO" fetch -q origin || fail "commit C"
C_SHA=$(git -C "$REPO" rev-parse HEAD)
TREE_A=$(git -C "$REPO" rev-parse "$A_SHA:backend/java/cretas-api")
TREE_B=$(git -C "$REPO" rev-parse "$B_SHA:backend/java/cretas-api")
[ "$TREE_A" != "$TREE_B" ] || fail "夹具坏了: A/B 的 Java 树相同"
[ "$(git -C "$REPO" rev-parse origin/main)" = "$C_SHA" ] || fail "夹具坏了: origin/main != C"

# ── 可信 jar 目录 ─────────────────────────────────────────────────────────
JARDIR="$TMP/jar"; mkdir -p "$JARDIR"
JAR_SHA=""
make_manifest() {  # $1 build_commit, $2 backend_tree, $3 success(默认 true)
    printf 'fake-jar-bytes-%s\n' "$1" > "$JARDIR/cretas-backend-system-1.0.0.jar"
    JAR_SHA=$(sha256_of "$JARDIR/cretas-backend-system-1.0.0.jar")
    cat > "$JARDIR/release-jar.manifest" <<EOF
format=cretas-release-jar-manifest-v1
success=${3:-true}
build_commit=$1
backend_tree=$2
jar_sha256=$JAR_SHA
jar_path=cretas-backend-system-1.0.0.jar
jdk_vendor=Test
jdk_version=21
maven_command=./mvnw clean package -Dtest=X
target_tests=X
maven_wall_seconds=1
jar_size_bytes=$(wc -c < "$JARDIR/cretas-backend-system-1.0.0.jar" | tr -d ' ')
EOF
}

# ── ssh / scp 桩: 按参数里的 RTB_STEP=<name> 分派, 记录每次调用与收到的 stdin ──
FAKEBIN="$TMP/bin"; STUB_DIR="$TMP/stub"; mkdir -p "$FAKEBIN" "$STUB_DIR"
export STUB_DIR
cat > "$FAKEBIN/ssh" <<'STUB'
#!/usr/bin/env bash
step=$(printf '%s' "$*" | grep -oE 'RTB_STEP=[a-z-]+' | head -1 | cut -d= -f2)
[ -n "$step" ] || { echo "ssh-stub: 参数里没有 RTB_STEP: $*" >&2; exit 97; }
echo "ssh $step" >> "$STUB_DIR/calls.log"
cat > "$STUB_DIR/$step.stdin"
rc=0; [ -f "$STUB_DIR/$step.rc" ] && rc=$(cat "$STUB_DIR/$step.rc")
[ -f "$STUB_DIR/$step.out" ] && cat "$STUB_DIR/$step.out"
exit "$rc"
STUB
cat > "$FAKEBIN/scp" <<'STUB'
#!/usr/bin/env bash
# 最后两个参数是 src dst; 只记 basename, 断言里好读。
n=$#; src=${@:$((n-1)):1}; dst=${@:$n:1}
echo "scp $(basename "$src") -> $dst" >> "$STUB_DIR/calls.log"
rc=0; [ -f "$STUB_DIR/scp.rc" ] && rc=$(cat "$STUB_DIR/scp.rc")
exit "$rc"
STUB
chmod +x "$FAKEBIN/ssh" "$FAKEBIN/scp"
# 桩的自检: 分派键真的从参数里取得到, 且 stdin 被收下 (被测脚本靠 stdin 送远端脚本)。
printf 'x=1\n' > "$STUB_DIR/selftest.out"
out=$(printf 'hello' | PATH="$FAKEBIN:$PATH" ssh -o BatchMode=yes host "RTB_STEP=selftest bash -s"); rc=$?
[ "$rc" = 0 ] && [ "$out" = "x=1" ] && [ "$(cat "$STUB_DIR/selftest.stdin")" = "hello" ] \
    || fail "ssh 桩自检失败 rc=$rc out=[$out]"
rm -f "$STUB_DIR/calls.log" "$STUB_DIR/selftest."*

precheck_out() {  # remote_jar_sha sidecar pid health flyway_max flyway_count tmp_sha [extra…]
    cat <<EOF
rtb_uid=501
tool_shasum=ok
tool_sha256sum=missing
tool_unzip=ok
tool_curl=ok
tool_launchctl=ok
tool_plutil=ok
tool_psql=ok
target_dir=ok
remote_jar_sha256=$1
remote_jar_size=100
remote_sidecar_build_commit=$2
remote_tmp_sha256=$7
launchd_label=${PRE_LABEL:-ok}
plist_refers_jar=${PRE_PLIST:-yes}
pid_before=$3
health_before=$4
flyway_db=cretas_testenv
flyway_user=cretas_user
flyway_max=$5
flyway_count=$6
flyway_installed_on=2026-09-06T07:35:16
precheck_done=1
EOF
    shift 7; local l; for l in "$@"; do printf '%s\n' "$l"; done
}
verify_out() { printf 'tmp_sha256=%s\ntmp_manifest_build_commit=%s\n' "$1" "$2"; }
install_out() {  # health pid_after sidecar_after flyway_max flyway_count [extra…]
    cat <<EOF
rtb_uid=501
install_stage=backup
backup=${INST_BACKUP:-/Users/rex/x/target/cretas-backend-system-1.0.0.jar.bak.20260906T000000Z}
install_stage=copy
install_stage=verify
installed_sha256=$JAR_SHA
install_stage=kickstart
kickstart_rc=${INST_KICK:-0}
install_stage=health
health_attempt=1 pid=100 code=000
health_attempt=2 pid=$2 code=$1
health_after=$1
health_attempts=2
health_paired=${INST_PAIRED:-yes}
pid_after=$2
install_stage=readback
sidecar_after=$3
git_properties=absent
flyway_db=cretas_testenv
flyway_user=cretas_user
flyway_max=$4
flyway_count=$5
flyway_installed_on=2026-09-06T07:53:37
install_done=1
EOF
    shift 5; local l; for l in "$@"; do printf '%s\n' "$l"; done
}
reset_stubs() {
    rm -f "$STUB_DIR"/*
    precheck_out oldsha000 "$A_SHA" 100 200 20261101.34 695 none > "$STUB_DIR/precheck.out"
    verify_out "$JAR_SHA" "$B_SHA" > "$STUB_DIR/verify-upload.out"
    install_out 200 101 "$B_SHA" 20261101.35 696 > "$STUB_DIR/install.out"
}

LEDGER="$TMP/ledger"; : > "$LEDGER"
RC=0; OUT=""; LINE=""
run_it() {  # 参数原样传给被测脚本
    local before after
    before=$(grep -c . "$LEDGER" || true)
    rm -f "$STUB_DIR/calls.log"
    OUT=$(PATH="$FAKEBIN:$PATH" RTB_REPO_ROOT="$REPO" RTB_JAR_DIR="$JARDIR" RTB_LEDGER="$LEDGER" \
          RTB_SSH_TARGET=stub-host RTB_HEALTH_TIMEOUT=10 RTB_HEALTH_INTERVAL=5 \
          bash "$SCRIPT" "$@" 2>&1); RC=$?
    after=$(grep -c . "$LEDGER" || true)
    # 硬约束 9: 台账恰好长 1 行 —— 「没长」是崩在判决之前, 「长 2」是判决写了两次。
    [ "$((after - before))" = 1 ] || fail "台账应恰好长 1 行, 实际 $((after - before)) (rc=$RC)。输出: $OUT"
    LINE=$(tail -1 "$LEDGER")
    echo "    verdict: $LINE"
}
calls() { [ -f "$STUB_DIR/calls.log" ] && cat "$STUB_DIR/calls.log" || true; }
assert_rc()    { [ "$RC" = "$1" ] || fail "$2: 期望 rc=$1, 实得 rc=$RC。输出: $OUT"; }
assert_line()  { case "$LINE" in *"$1"*) ;; *) fail "$2: 判决行里没有 [$1]" ;; esac; }
assert_out()   { case "$OUT" in *"$1"*) ;; *) fail "$2: 输出里没有 [$1]" ;; esac; }
assert_no_call() { case "$(calls)" in *"$1"*) fail "$2: 不该调用 [$1], 实际调用: $(calls | tr '\n' ';')" ;; esac; }
assert_call()    { case "$(calls)" in *"$1"*) ;; *) fail "$2: 应调用 [$1], 实际调用: $(calls | tr '\n' ';')" ;; esac; }
RC2_REASONS=""
note_rc2() { local r; r=$(printf '%s' "$LINE" | grep -oE 'reason=[a-z-]+' | head -1); case " $RC2_REASONS " in *" $r "*) ;; *) RC2_REASONS="$RC2_REASONS $r" ;; esac; }

# ═════════ 1. 主路径: jar == main 的 Java 树 ⇒ REFRESHED, 七步顺序正确 ═════════
make_manifest "$B_SHA" "$TREE_B"; reset_stubs
run_it --confirm YES-TESTENV
assert_rc 0 "[1]"; assert_line "REFRESHED" "[1]"
assert_line "flyway_before=20261101.34/695" "[1]"; assert_line "flyway_after=20261101.35/696" "[1]"
assert_line "pid_before=100 pid_after=101" "[1]"; assert_line "provenance=manifest-sidecar" "[1]"
assert_line "backup=/Users/rex/x/target/cretas-backend-system-1.0.0.jar.bak." "[1]"
assert_line "main=${C_SHA:0:10}" "[1] jar 构建自 B, main 是 C (只改了 web-admin) —— Java 树相同必须放行"
expected_calls='ssh precheck
scp cretas-backend-system-1.0.0.jar -> stub-host:/tmp/cretas-main-'"${B_SHA:0:8}"'.jar
scp release-jar.manifest -> stub-host:/tmp/cretas-main-'"${B_SHA:0:8}"'.jar.manifest
ssh verify-upload
ssh install'
[ "$(calls)" = "$expected_calls" ] || fail "[1] 远端调用顺序不对:
$(calls)
--- 期望 ---
$expected_calls"
# 送到远端的脚本本身也要看一眼: 备份 / kickstart / sha 期望值 / PATH 钉住
grep -q 'cp -p "$J" "$BAK"' "$STUB_DIR/install.stdin" || fail "[1] install 脚本里没有 cp -p 备份"
grep -q 'launchctl kickstart -k "gui/$uid_/$L"' "$STUB_DIR/install.stdin" || fail "[1] install 脚本里没有 kickstart -k"
grep -q "EXPECT=\"$JAR_SHA\"" "$STUB_DIR/install.stdin" || fail "[1] install 脚本没带本机 sha 作期望值"
grep -q 'export PATH="/opt/homebrew/bin:' "$STUB_DIR/precheck.stdin" || fail "[1] 远端脚本没自己钉 PATH (非交互 ssh 到 macOS 找不到 psql)"
grep -q 'flyway_schema_history' "$STUB_DIR/precheck.stdin" && grep -q 'flyway_schema_history' "$STUB_DIR/install.stdin" \
    || fail "[1] 前/后两次都得读 flyway_schema_history"
grep -q 'plutil -extract EnvironmentVariables.DB_PASSWORD' "$STUB_DIR/precheck.stdin" || fail "[1] DB 密码应在远端从 plist 读, 不经本机"
ok "REFRESHED: 顺序 precheck→scp→verify→install, 判决行含 flyway 前后 / pid 前后 / backup"

# ═════════ 2. jar 落后 main ⇒ REFUSED, 且一次远端都没碰 ═══════════════════
make_manifest "$A_SHA" "$TREE_A"; reset_stubs
run_it --confirm YES-TESTENV
assert_rc 3 "[2]"; assert_line "REFUSED reason=jar-behind-main" "[2]"; assert_line "java_diff_files=1" "[2]"
[ -z "$(calls)" ] || fail "[2] 拒绝之后不该有任何远端调用, 实际: $(calls | tr '\n' ';')"
assert_out "prewarm-main-artifact.sh" "[2] 拒绝时要告诉人下一步"
ok "jar 落后 main ⇒ rc=3 REFUSED, 零远端接触 (阴性对照: calls.log 为空)"

# ═════════ 3. 传输后 sha 不一致 ⇒ rc=2, install 没被调 ═══════════════════
make_manifest "$B_SHA" "$TREE_B"; reset_stubs
verify_out deadbeef "$B_SHA" > "$STUB_DIR/verify-upload.out"
run_it --confirm YES-TESTENV
assert_rc 2 "[3]"; assert_line "INSTRUMENT_DEAD reason=transfer-sha-mismatch" "[3]"; note_rc2
assert_call "ssh verify-upload" "[3]"; assert_no_call "ssh install" "[3] sha 对不上就不许装"
ok "传输 sha 不一致 ⇒ rc=2, install 未调用"

# ═════════ 4. health 超时 ⇒ rc=1, 判决行带 backup, 输出带回滚命令 ════════
reset_stubs; install_out 000 101 "$B_SHA" 20261101.35 696 > "$STUB_DIR/install.out"
run_it --confirm YES-TESTENV
assert_rc 1 "[4]"; assert_line "PROBLEM reason=health-timeout" "[4]"; assert_line "backup=" "[4]"
assert_out "回滚:" "[4]"; assert_out "launchctl kickstart -k" "[4]"
ok "health 超时 ⇒ rc=1 PROBLEM, 判决行有 backup=, 输出有回滚命令"

# ═════════ 5. 不带 --confirm ⇒ DRY_RUN, 只有 precheck ═════════════════════
reset_stubs
run_it
assert_rc 0 "[5]"; assert_line "DRY_RUN" "[5]"; assert_line "would_upload=scp" "[5]"
[ "$(calls)" = "ssh precheck" ] || fail "[5] dry-run 只该调 precheck, 实际: $(calls | tr '\n' ';')"
ok "dry-run: 只读远端, 不传不装"

# ═════════ 5b. --confirm 值不对 ⇒ 仍是 dry-run (⛔ 不许把打错的确认词当成确认) ═
reset_stubs
run_it --confirm yes
assert_rc 0 "[5b]"; assert_line "DRY_RUN" "[5b]"; assert_no_call "ssh install" "[5b]"
ok "--confirm 打错 ⇒ dry-run"

# ═════════ 6. 远端已是同一个 jar (sha + sidecar 双相等) ⇒ ALREADY_CURRENT, 不重启 ═
reset_stubs; precheck_out "$JAR_SHA" "$B_SHA" 100 200 20261101.35 696 none > "$STUB_DIR/precheck.out"
run_it --confirm YES-TESTENV
assert_rc 0 "[6]"; assert_line "ALREADY_CURRENT" "[6]"
[ "$(calls)" = "ssh precheck" ] || fail "[6] 已是当前 jar 不该重启, 实际: $(calls | tr '\n' ';')"
# 阴性对照: 只有 sha 相等、sidecar 不等 ⇒ 不算 current (说不清上面那个是谁放的)
reset_stubs; precheck_out "$JAR_SHA" "$A_SHA" 100 200 20261101.35 696 none > "$STUB_DIR/precheck.out"
run_it
assert_line "DRY_RUN" "[6b] sha 相等但 sidecar 不等 ⇒ 不是 ALREADY_CURRENT"
ok "ALREADY_CURRENT 要 sha 与 sidecar 双相等; 单 sha 相等不算"

# ═════════ 6c. 远端 /tmp 已有同 sha 的临时件 ⇒ 跳过 170MB 传输, 仍传 manifest ═
reset_stubs; precheck_out oldsha000 "$A_SHA" 100 200 20261101.34 695 "$JAR_SHA" > "$STUB_DIR/precheck.out"
run_it --confirm YES-TESTENV
assert_rc 0 "[6c]"; assert_line "upload=reuse-tmp" "[6c]"
assert_no_call "scp cretas-backend-system-1.0.0.jar" "[6c] 同 sha 的临时件在, 不该重传 jar"
assert_call "scp release-jar.manifest" "[6c] manifest sidecar 仍要传"
ok "远端临时件同 sha ⇒ 跳过 jar 传输 (upload=reuse-tmp), manifest 照传"

# ═════════ 7-11. rc=2 各构造一次 ══════════════════════════════════════════
reset_stubs; rm -f "$JARDIR/release-jar.manifest"
run_it; assert_rc 2 "[7]"; assert_line "INSTRUMENT_DEAD reason=manifest-missing" "[7]"; note_rc2
ok "rc=2 manifest-missing"

make_manifest "$B_SHA" "$TREE_B"; rm -f "$JARDIR/cretas-backend-system-1.0.0.jar"; reset_stubs
run_it; assert_rc 2 "[7b]"; assert_line "reason=jar-missing" "[7b]"; note_rc2
ok "rc=2 jar-missing"

make_manifest "$B_SHA" "$TREE_A"; reset_stubs   # manifest 自己说的 tree 与 git 不符
run_it; assert_rc 2 "[7c]"; assert_line "reason=manifest-inconsistent" "[7c]"; note_rc2
[ -z "$(calls)" ] || fail "[7c] manifest 不自洽不该碰远端"
ok "rc=2 manifest-inconsistent (manifest.backend_tree ≠ git 的 build_commit 树)"

make_manifest "$B_SHA" "$TREE_B"; reset_stubs; printf '255' > "$STUB_DIR/precheck.rc"; : > "$STUB_DIR/precheck.out"
run_it; assert_rc 2 "[8]"; assert_line "reason=ssh-unreachable" "[8]"; note_rc2
ok "rc=2 ssh-unreachable (precheck ssh rc=255)"

reset_stubs; sed -i.bak 's/^tool_psql=ok/tool_psql=missing/' "$STUB_DIR/precheck.out"
run_it; assert_rc 2 "[9]"; assert_line "reason=remote-tool-missing" "[9]"; assert_out "psql" "[9] 要说出缺的是哪个"; note_rc2
ok "rc=2 remote-tool-missing (远端缺 psql)"

reset_stubs; precheck_out oldsha000 "$A_SHA" 100 200 "" "" none 'flyway_error=psql: FATAL: password authentication failed' \
    | grep -v '^flyway_max=$' | grep -v '^flyway_count=$' > "$STUB_DIR/precheck.out"
run_it; assert_rc 2 "[10]"; assert_line "reason=flyway-unreadable" "[10]"; assert_out "password authentication failed" "[10]"; note_rc2
assert_no_call "ssh install" "[10]"
ok "rc=2 flyway-unreadable (前读数拿不到就不刷)"

reset_stubs; git -C "$REPO" remote set-url origin "$TMP/does-not-exist.git"
run_it; assert_rc 2 "[11]"; assert_line "reason=fetch-failed" "[11]"; note_rc2
[ -z "$(calls)" ] || fail "[11] fetch 失败不该碰远端"
run_it --allow-stale-main
assert_rc 0 "[11b]"; assert_line "DRY_RUN" "[11b]"; assert_line "main_ref=stale" "[11b] 用了缓存 ref 必须在判决行标出来"
git -C "$REPO" remote set-url origin "$ORIGIN"
ok "rc=2 fetch-failed; --allow-stale-main 放行但判决行标 main_ref=stale"

reset_stubs; printf '1' > "$STUB_DIR/scp.rc"
run_it --confirm YES-TESTENV; assert_rc 2 "[11c]"; assert_line "reason=scp-failed" "[11c]"; note_rc2
assert_no_call "ssh install" "[11c]"
ok "rc=2 scp-failed"

reset_stubs; printf '255' > "$STUB_DIR/install.rc"
printf 'rtb_uid=501\ninstall_stage=backup\nbackup=/x.bak\ninstall_stage=copy\n' > "$STUB_DIR/install.out"
run_it --confirm YES-TESTENV; assert_rc 2 "[11d]"; assert_line "reason=ssh-dropped-during-install" "[11d]"; note_rc2
assert_out "最后到达阶段: copy" "[11d] 要说出远端到了哪一步"
assert_out "不要读成「应用起不来」" "[11d]"
ok "rc=2 ssh-dropped-during-install (装到一半断线 ≠ 应用死了)"

reset_stubs; install_out 200 101 "$B_SHA" "" "" 'flyway_error=psql: connection refused' | grep -v '^flyway_max=$' | grep -v '^flyway_count=$' > "$STUB_DIR/install.out"
run_it --confirm YES-TESTENV; assert_rc 2 "[11e]"; assert_line "reason=flyway-unreadable-after" "[11e]"; note_rc2
assert_out "deployed=yes" "[11e] 后读数没拿到但 jar 已上去, 必须说明"
ok "rc=2 flyway-unreadable-after (判决行说 deployed=yes)"

# ═════════ 12-13. rc=1 的另两种 ══════════════════════════════════════════
reset_stubs; install_out 200 100 "$B_SHA" 20261101.35 696 > "$STUB_DIR/install.out"   # pid 没变
run_it --confirm YES-TESTENV; assert_rc 1 "[12]"; assert_line "PROBLEM reason=pid-unchanged-after-kickstart" "[12]"
ok "rc=1 pid-unchanged-after-kickstart (health 200 在没重启时也成立, pid 才是必发生读数)"

reset_stubs; install_out 200 101 "$A_SHA" 20261101.35 696 > "$STUB_DIR/install.out"   # sidecar 读回不对
run_it --confirm YES-TESTENV; assert_rc 1 "[13]"; assert_line "reason=sidecar-readback-mismatch" "[13]"
ok "rc=1 sidecar-readback-mismatch"

reset_stubs; install_out 000 101 "$B_SHA" 20261101.35 696 'install_failed=verify' > "$STUB_DIR/install.out"
run_it --confirm YES-TESTENV; assert_rc 1 "[13b]"; assert_line "reason=install-verify-failed" "[13b]"
ok "rc=1 install-verify-failed (覆盖后 sha 不对)"

# ═════════ 14-15. REFUSED 的另两种 ════════════════════════════════════════
make_manifest "$B_SHA" "$TREE_B"; printf 'corrupted' >> "$JARDIR/cretas-backend-system-1.0.0.jar"; reset_stubs
run_it --confirm YES-TESTENV; assert_rc 3 "[14]"; assert_line "REFUSED reason=local-jar-sha-mismatch" "[14]"
[ -z "$(calls)" ] || fail "[14] 本机 jar 不自洽不该碰远端"
ok "rc=3 local-jar-sha-mismatch"

make_manifest "$B_SHA" "$TREE_B" false; reset_stubs
run_it; assert_rc 3 "[15]"; assert_line "REFUSED reason=manifest-not-success" "[15]"
ok "rc=3 manifest-not-success"

# ═════════ 16. 用法错 ⇒ rc=2 (在台账之前退出, 不走 run_it) ══════════════
make_manifest "$B_SHA" "$TREE_B"; reset_stubs
PATH="$FAKEBIN:$PATH" RTB_REPO_ROOT="$REPO" RTB_JAR_DIR="$JARDIR" RTB_LEDGER="$LEDGER" bash "$SCRIPT" --bogus >/dev/null 2>&1; rc=$?
[ "$rc" = 2 ] || fail "[16] 未知参数应 rc=2, 实得 $rc"
PATH="$FAKEBIN:$PATH" RTB_REPO_ROOT="$REPO" RTB_JAR_DIR="$JARDIR" RTB_LEDGER="$LEDGER" bash "$SCRIPT" --health-timeout abc >/dev/null 2>&1; rc=$?
[ "$rc" = 2 ] || fail "[16] --health-timeout 非整数应 rc=2, 实得 $rc"
ok "用法错 ⇒ rc=2"

# ═════════ A. 目录在、jar 不在 ⇒ REFUSED (除非 --allow-new-install); plist 不引用 $J ⇒ REFUSED ═
make_manifest "$B_SHA" "$TREE_B"; reset_stubs
precheck_out none none "" 000 20261101.34 695 none > "$STUB_DIR/precheck.out"
run_it --confirm YES-TESTENV
assert_rc 3 "[A1]"; assert_line "REFUSED reason=remote-jar-missing" "[A1]"
[ "$(calls)" = "ssh precheck" ] || fail "[A1] jar 不在时不该传/装, 实际: $(calls | tr '\n' ';')"
ok "远端 jar 不在 ⇒ rc=3 remote-jar-missing, 零写入"
reset_stubs; precheck_out none none "" 000 20261101.34 695 none > "$STUB_DIR/precheck.out"
INST_BACKUP=none-no-previous-jar install_out 200 101 "$B_SHA" 20261101.35 696 > "$STUB_DIR/install.out"
run_it --confirm YES-TESTENV --allow-new-install
assert_rc 0 "[A1b]"; assert_line "REFRESHED" "[A1b]"; assert_line "backup=none-no-previous-jar" "[A1b]"
ok "--allow-new-install ⇒ 首次安装放行, 判决行 backup=none-no-previous-jar"
reset_stubs; PRE_PLIST=no precheck_out oldsha000 "$A_SHA" 100 200 20261101.34 695 none > "$STUB_DIR/precheck.out"
run_it --confirm YES-TESTENV
assert_rc 3 "[A2]"; assert_line "REFUSED reason=plist-jar-mismatch" "[A2]"
[ "$(calls)" = "ssh precheck" ] || fail "[A2] plist 不引用 jar 时不该传/装"
ok "plist ProgramArguments 不引用 \$J ⇒ rc=3 plist-jar-mismatch"
grep -q 'plutil -extract ProgramArguments xml1' "$STUB_DIR/precheck.stdin" || fail "[A2] 远端要用 xml1 取 ProgramArguments (json 会把 / 转义成 \\/, 字面 grep 恒 0)"
ok "远端 plist 断言用 xml1 (json 转义会让读数恒 0)"

# ═════════ B. launchd 标签不在 ⇒ 预检 rc=2, 零写入; kickstart rc≠0 ⇒ rc=1 ══════
reset_stubs; PRE_LABEL='missing rc=113 Could not find service' precheck_out oldsha000 "$A_SHA" "" 200 20261101.34 695 none > "$STUB_DIR/precheck.out"
run_it --confirm YES-TESTENV
assert_rc 2 "[B1]"; assert_line "INSTRUMENT_DEAD reason=launchd-label-missing" "[B1]"; note_rc2
assert_out "Could not find service" "[B1] launchctl 的 stderr 要贴出来"
[ "$(calls)" = "ssh precheck" ] || fail "[B1] 标签不在时不该传/装, 实际: $(calls | tr '\n' ';')"
grep -q 'lpid() { launchctl print "gui/$uid_/$L" 2>/dev/null' "$STUB_DIR/precheck.stdin" && fail "[B1] lpid 仍在吞 launchctl 的 stderr"
ok "launchd 标签不在 ⇒ rc=2 launchd-label-missing, 零写入 (原来: pid 空 ⇒ 预检过 ⇒ 覆盖后才 rc=1)"
reset_stubs; INST_KICK=1 install_out 200 101 "$B_SHA" 20261101.35 696 'kickstart_out=Could not find service' > "$STUB_DIR/install.out"
run_it --confirm YES-TESTENV
assert_rc 1 "[B2]"; assert_line "PROBLEM reason=kickstart-failed" "[B2]"; assert_out "Could not find service" "[B2]"
ok "kickstart rc=1 ⇒ rc=1 kickstart-failed (kickstart_rc 打了就要判)"

# ═════════ C. 200 必须与同一轮的新 pid 配对 ════════════════════════════════
reset_stubs; INST_PAIRED=no install_out 200 101 "$B_SHA" 20261101.35 696 > "$STUB_DIR/install.out"
run_it --confirm YES-TESTENV
assert_rc 1 "[C1]"; assert_line "PROBLEM reason=health-not-paired-with-new-pid" "[C1]"
ok "health=200 且事后 pid 变了但没配对 ⇒ rc=1 (原来这正是 REFRESHED 假绿)"

# ── C-replay: 把生成的远端 install.sh 在假 launchctl/curl 下**真跑**一遍 ──────────
# 竞态: 旧进程在第 1 轮答 200; pid 在第 1 次探测之后才翻成 101。
# 正确的循环要到第 2 轮 (pid=101 且 200) 才收; 「只看 200」的循环第 1 轮就收了旧进程的 200。
RB="$TMP/rbin"; RSTUB="$TMP/rstub"; RT="$TMP/remote/target"; mkdir -p "$RB" "$RSTUB" "$RT"
for t in cp awk sed grep head tr wc cat cut mkdir rm mv sha256sum; do
    abs=$(command -v "$t") || fail "本机缺 $t"; printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$RB/$t"; chmod +x "$RB/$t"
done
printf '#!/bin/sh\nexit 0\n' > "$RB/sleep"; printf '#!/bin/sh\necho 501\n' > "$RB/id"; printf '#!/bin/sh\nexit 1\n' > "$RB/unzip"
cat > "$RB/curl" <<'S'
#!/bin/sh
n=0; [ -f "$RSTUB/curl.calls" ] && n=$(cat "$RSTUB/curl.calls"); n=$((n+1)); echo "$n" > "$RSTUB/curl.calls"
printf '200'
S
cat > "$RB/launchctl" <<'S'
#!/bin/sh
case "$1" in
  print) n=0; [ -f "$RSTUB/curl.calls" ] && n=$(cat "$RSTUB/curl.calls")
         if [ "$n" -ge 1 ]; then echo "	pid = 101"; else echo "	pid = 100"; fi ;;
  kickstart) echo "kickstart $*" >> "$RSTUB/calls.log" ;;
esac
exit 0
S
cat > "$RB/plutil" <<'S'
#!/bin/sh
case "$*" in *DB_PASSWORD*) echo pw ;; *DB_NAME*) echo cretas_testenv ;; *DB_USERNAME*) exit 1 ;; *) echo "<string>$REPLAY_J</string>" ;; esac
S
printf '#!/bin/sh\necho "20261101.35|696|2026-09-06 15:53:37"\n' > "$RB/psql"
chmod +x "$RB"/*
export RSTUB
# 先用假 ssh 桩跑一次主路径, 拿到**生成的** install.sh (它带着 EXPECT/PID_BEFORE/路径)。
reset_stubs
printf 'fake-jar-bytes-%s\n' "$B_SHA" > "$TMP/remote/tmp.jar"; cp "$JARDIR/release-jar.manifest" "$TMP/remote/tmp.jar.manifest"
printf 'old-jar\n' > "$RT/cretas-backend-system-1.0.0.jar"
RTB_REMOTE_TARGET_DIR="$RT" RTB_REMOTE_TMP="$TMP/remote/tmp.jar" RTB_REMOTE_PLIST="$TMP/remote/plist" RTB_REMOTE_PATH="$RB" \
    run_it --confirm YES-TESTENV
assert_rc 0 "[C-gen]"
GEN="$STUB_DIR/install.stdin"; [ -s "$GEN" ] || fail "[C-replay] 没拿到生成的 install.sh"
grep -q 'PID_BEFORE="100"' "$GEN" || fail "[C-replay] 生成的 install.sh 没带 PID_BEFORE"
export REPLAY_J="$RT/cretas-backend-system-1.0.0.jar"
rm -f "$RSTUB/curl.calls" "$RSTUB/calls.log"
HOME="$TMP" RTB_STEP=install bash "$GEN" > "$TMP/replay.out" 2> "$TMP/replay.err"; rrc=$?
echo "    replay rc=$rrc: $(grep -E '^(health_attempt|health_after|health_attempts|health_paired|pid_after|installed_sha256|install_done)=' "$TMP/replay.out" | tr '\n' ' ')"
[ "$rrc" = 0 ] || fail "[C-replay] 生成脚本自己跑挂了: $(head -3 "$TMP/replay.err")"
grep -q '^install_done=1$' "$TMP/replay.out" || fail "[C-replay] 没跑到 install_done"
grep -q '^health_attempt=1 pid=100 code=200$' "$TMP/replay.out" || fail "[C-replay] 第 1 轮应是旧 pid=100 答 200 (竞态没构造出来)"
grep -q '^health_paired=yes$' "$TMP/replay.out" || fail "[C-replay] 应在 pid 翻成 101 后配对成功"
grep -q '^health_attempts=2$' "$TMP/replay.out" || fail "[C-replay] 应在第 2 轮才收 —— 第 1 轮那个 200 是旧进程的"
grep -q '^pid_after=101$' "$TMP/replay.out" || fail "[C-replay] pid_after 应是 101"
grep -q "^installed_sha256=$JAR_SHA$" "$TMP/replay.out" || fail "[C-replay] 覆盖后 sha 不对"
grep -q 'kickstart -k gui/501/com.cretas.testenv.backend' "$RSTUB/calls.log" || fail "[C-replay] kickstart 没被调"
ls "$RT"/cretas-backend-system-1.0.0.jar.bak.* >/dev/null 2>&1 || fail "[C-replay] 没有备份"
[ "$(cat "$RT/cretas-backend-system-1.0.0.jar.manifest" | grep -c "build_commit=$B_SHA")" = 1 ] || fail "[C-replay] sidecar 没装上"
ok "C-replay: 生成的 install.sh 真跑 —— 旧 pid 的 200 不收, 第 2 轮 pid=101 才配对; 备份/覆盖/sidecar/kickstart 都到位"

# ═════════ 17. 只有 0 是绿: 每种非 0 判决都不含 REFRESHED ════════════════
grep -E 'rc=[123]$' "$LEDGER" | grep -q 'REFRESHED' && fail "[17] 有非 0 的判决行写着 REFRESHED"
grep -E 'rc=0$' "$LEDGER" | grep -qvE '^[^ ]+ (REFRESHED|DRY_RUN|ALREADY_CURRENT) ' && fail "[17] 有 rc=0 的判决行不是三种绿之一"
ok "台账里 rc 与判决词一一对应"

N_RC2=$(printf '%s' "$RC2_REASONS" | wc -w | tr -d ' ')
echo "READING: RC2_REASONS_CONSTRUCTED=$N_RC2 [${RC2_REASONS# }]"
echo "READING: CASES=$PASSED"
[ "$N_RC2" -ge 12 ] || fail "rc=2 原因只构造到 $N_RC2 种 (期望 ≥ 12) —— 三态的价值全在第三态"
echo "PASS: test-refresh-testenv-backend.sh ($PASSED 组断言)"
