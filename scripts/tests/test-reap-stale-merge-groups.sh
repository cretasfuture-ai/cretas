#!/bin/bash
# scripts/ci/canada-mini-gate/reap-stale-merge-groups.sh 的场景测试 (假 gh, 不连网)。
# 守的行为: 只取消「头 SHA 不在合并队列里、且够老」的 merge_group run; 队列/run 列表读不到时一个都不取消 (rc=2)。
# mini 上是 /bin/bash 3.2: SUT_BASH=/bin/bash 可指定解释器 (在 mini 上手跑一遍兼容性)。
set -uo pipefail
cd "$(dirname "$0")/../.."
SUT=${SUT:-scripts/ci/canada-mini-gate/reap-stale-merge-groups.sh}
SUT_BASH=${SUT_BASH:-bash}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0; n=0

cat > "$T/gh" <<'EOF'
#!/bin/bash
F=$FAKE_DIR
args="$*"
case "$args" in
  "api graphql"*)
    c=$(cat "$F/qcalls" 2>/dev/null || echo 0); c=$((c + 1)); echo "$c" > "$F/qcalls"
    [ -e "$F/queue-fail" ] && exit 1
    if [ "$c" -ge 2 ] && [ -e "$F/live2" ]; then cat "$F/live2"; else cat "$F/live" 2>/dev/null; fi; exit 0 ;;
  "api --paginate "*)
    st=${args#*status=}; st=${st%%&*}
    [ -e "$F/runs-fail" ] && exit 1
    cat "$F/runs-$st" 2>/dev/null; exit 0 ;;
  "api -X POST "*)
    id=${args#*runs/}; id=${id%%/*}
    echo "$id" >> "$F/cancelled"
    grep -qx "$id" "$F/cancel-fail" 2>/dev/null && exit 1
    exit 0 ;;
  "api repos/"*"/actions/runs/"*)
    id=${args#*runs/}; id=${id%% *}
    cat "$F/status-$id" 2>/dev/null || echo in_progress ;;
  *) echo "fake gh: unexpected: $args" >&2; exit 3 ;;
esac
EOF
chmod +x "$T/gh"

reset() { rm -rf "$T/f"; mkdir -p "$T/f"; }
run() { FAKE_DIR=$T/f GH=$T/gh MIN_AGE_SEC=180 "$SUT_BASH" "${SUTX:-$SUT}" "$@" > "$T/out" 2>&1; echo $? > "$T/rc"; }
check() {  # <名> <期望 rc> <期望被取消的 id, 空格分隔, 排序>
  local got_rc got_c=""; got_rc=$(cat "$T/rc")
  # 不用管道: 先落变量再用不加引号的展开把换行并成单空格 (pipe-rc 闸)
  if [ -f "$T/f/cancelled" ]; then got_c=$(sort -n "$T/f/cancelled") || got_c=SORT_FAILED; got_c=$(echo $got_c); fi
  n=$((n + 1))
  if [ "$got_rc" = "$2" ] && [ "$got_c" = "$3" ]; then echo "PASS $1 (rc=$got_rc cancelled=[$got_c])"
  else echo "FAIL $1: rc=$got_rc want=$2 cancelled=[$got_c] want=[$3]"; sed 's/^/    /' "$T/out"; fail=1; fi
}

# 1 基本: A 活; B 失效且老 ⇒ 取消; C 失效但刚建 ⇒ 跳过
reset; echo A > "$T/f/live"; printf '1 A 500\n2 B 500\n3 C 10\n' > "$T/f/runs-queued"
run; check "失效且老才取消, 活的和刚建的不动" 0 "2"
grep -q 'stale=1 young_skipped=1 cancelled=1 failed=0' "$T/out" && { echo "PASS READING 行计数对"; n=$((n+1)); } || { echo "FAIL READING 行"; cat "$T/out"; fail=1; }

# 2 队列读不到 ⇒ rc=2, 一个都不取消
reset; touch "$T/f/queue-fail"; printf '2 B 500\n' > "$T/f/runs-queued"
run; check "队列读不到 ⇒ 没量到, 不取消" 2 ""

# 3 run 列表读不到 ⇒ rc=2
reset; echo A > "$T/f/live"; touch "$T/f/runs-fail"
run; check "run 列表读不到 ⇒ 没量到" 2 ""

# 4 列 run 期间新排出的组 D (只在第二次队列查询里出现) 不误杀
reset; echo A > "$T/f/live"; printf 'A\nD\n' > "$T/f/live2"; printf '4 D 500\n5 B 500\n' > "$T/f/runs-in_progress"
run; check "第二次查询才出现的组不误杀" 0 "5"

# 5 同一 run 在两个状态列表里出现 ⇒ 只取消一次
reset; echo A > "$T/f/live"; printf '6 B 500\n' > "$T/f/runs-queued"; printf '6 B 500\n' > "$T/f/runs-in_progress"
run; check "重复 id 只取消一次" 0 "6"

# 6 取消失败: 已结束算成功; 仍在跑算失败 rc=1
reset; echo A > "$T/f/live"; printf '7 B 500\n8 C 500\n' > "$T/f/runs-queued"; printf '7\n8\n' > "$T/f/cancel-fail"; echo completed > "$T/f/status-7"
run; check "取消失败: 已结束不算失败, 仍在跑 ⇒ rc=1" 1 "7 8"
grep -q 'CANCEL_FAILED run 8' "$T/out" && { echo "PASS 失败那条逐条打出"; n=$((n+1)); } || { echo "FAIL 没打 CANCEL_FAILED"; fail=1; }

# 7 队列为空 (全部合入了) ⇒ 所有老的 merge_group run 都失效
reset; : > "$T/f/live"; printf '9 B 500\n' > "$T/f/runs-queued"
run; check "队列为空 ⇒ 老 run 全取消" 0 "9"

# 8 --dry-run 不取消
reset; echo A > "$T/f/live"; printf '10 B 500\n' > "$T/f/runs-queued"
run --dry-run; check "--dry-run 不取消" 0 ""
grep -q 'DRY: would cancel run 10' "$T/out" && { echo "PASS dry-run 打出将取消的 run"; n=$((n+1)); } || { echo "FAIL dry-run 行"; fail=1; }

# 变异对照: 去掉第二次队列查询的并集 ⇒ 场景 4 必须把 D 误杀 (证明场景 4 守得住并集)
sed 's|done <<< "\$live1\$NL\$live2"|done <<< "$live1"|' "$SUT" > "$T/mut.sh"
if grep -q 'done <<< "$live1"$' "$T/mut.sh"; then
  reset; echo A > "$T/f/live"; printf 'A\nD\n' > "$T/f/live2"; printf '4 D 500\n5 B 500\n' > "$T/f/runs-in_progress"
  SUTX=$T/mut.sh run
  got=$(sort -n "$T/f/cancelled" | tr '\n' ' ')
  [ "$got" = "4 5 " ] && { echo "PASS 变异(去掉并集)把 D 误杀 ⇒ 场景 4 有效"; n=$((n+1)); } || { echo "FAIL 变异没让 D 被误杀: [$got]"; fail=1; }
else echo "FAIL 变异没打进去"; fail=1; fi

# 接线 (形态 B): refill.sh 的 beat 真的调用清理, 调的是 setup.sh 拷过去的那个路径
R=scripts/ci/canada-mini-gate/refill.sh
if [ -f "$R" ]; then
  beat_body=$(awk '/^beat\(\) \{/{on=1} on{print} on&&/^\}/{exit}' "$R")
  if printf '%s\n' "$beat_body" | grep -q '^  reap_stale_merge_groups$' \
     && grep -q '/Users/rex/gh-gate/reap-stale-merge-groups.sh' "$R" \
     && grep -q 'cp /tmp/gh-gate-reap.sh /Users/rex/gh-gate/reap-stale-merge-groups.sh' scripts/ci/canada-mini-gate/setup.sh; then
    echo "PASS 接线: beat 调 reap_stale_merge_groups, 路径与 setup.sh 一致"; n=$((n+1))
  else echo "FAIL 接线: beat 没调清理或路径与 setup.sh 不一致"; fail=1; fi
else echo "SKIP 接线 (只拷了被测脚本, 如在 mini 上跑兼容性)"; n=$((n+1)); fi

echo "cases=$n fail=$fail"
[ "$n" -ge 13 ] || { echo "FAIL 用例数 $n < 12, 没跑完"; exit 1; }
exit $fail
