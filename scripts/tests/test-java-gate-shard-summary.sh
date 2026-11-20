#!/usr/bin/env bash
# scripts/ci/java-gate-shard-summary.sh 的场景测试 (假 curl, 不连网)。
# 守的行为: 汇总作业只在「本 run 被取消且同一头 SHA 上已有更新的 run」时放行, 其它非 success 一律红;
# 并且 workflow 里两个汇总作业真的调用了它 (形态 B: 机制在但没接上)。
set -uo pipefail
cd "$(dirname "$0")/../.."
SUT=scripts/ci/java-gate-shard-summary.sh
WF=.github/workflows/java-gate-pr.yml
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0; n=0

# 假 curl: 按 URL 回 $T/run.json 或 $T/runs.json; $T/api-fail 存在时退出 22 (模拟 -f 的 HTTP 错误)。
cat > "$T/curl" <<'EOF'
#!/usr/bin/env bash
for a in "$@"; do url=$a; done
echo "$url" >> "$FAKE_DIR/calls"
[ -e "$FAKE_DIR/api-fail" ] && exit 22
case "$url" in
  */actions/runs/*) cat "$FAKE_DIR/run.json" ;;
  */actions/workflows/*/runs*) cat "$FAKE_DIR/runs.json" ;;
  *) exit 22 ;;
esac
EOF
chmod +x "$T/curl"

# case <名> <期望 rc> <result> <event> <runs.json 里的 id 列表> [api-fail]
case_() {
  local label=$1 want=$2 result=$3 event=$4 ids=$5 apifail=${6-}
  rm -f "$T/api-fail" "$T/calls"; [ -n "$apifail" ] && touch "$T/api-fail"
  echo '{"workflow_id": 777}' > "$T/run.json"
  printf '{"workflow_runs": [%s]}' "$(for i in $ids; do printf '{"id":%s},' "$i"; done | sed 's/,$//')" > "$T/runs.json"
  FAKE_DIR=$T CURL="$T/curl" EVENT_NAME=$event HEAD_SHA=abc123 GITHUB_RUN_ID=1000 \
    GITHUB_REPOSITORY=o/r GITHUB_TOKEN=x bash "$SUT" fast-contracts "$result" > "$T/out.log" 2>&1
  local rc=$?; n=$((n+1))
  if [ "$rc" = "$want" ]; then echo "PASS rc=$rc $label"; else echo "FAIL rc=$rc want=$want $label"; sed 's/^/    /' "$T/out.log"; fail=1; fi
}

case_ "success 直接绿"                         0 success   pull_request ""
case_ "failure 红"                             1 failure   pull_request "1001"
case_ "skipped 红 (不被当成通过)"              1 skipped   pull_request "1001"
case_ "空结论红"                               1 ""        pull_request "1001"
case_ "cancelled + 更新的 run ⇒ 放行"          0 cancelled pull_request "999 1000 1001"
case_ "cancelled + 只有更旧/自己 ⇒ 真取消红"   1 cancelled pull_request "998 1000"
case_ "cancelled + API 失败 ⇒ 红"              1 cancelled pull_request "1001" api-fail
case_ "cancelled + merge_group ⇒ 红"           1 cancelled merge_group  "1001"
grep -q '^SUPERSEDED: run 1000 .* run 1001' <(case_ "放行时打 SUPERSEDED 行" 0 cancelled pull_request "1001" >/dev/null; cat "$T/out.log") \
  && { echo "PASS SUPERSEDED 行带两个 run id"; n=$((n+1)); } || { echo "FAIL 放行时没打 SUPERSEDED 行"; fail=1; }

# 变异对照: 把「id 更大」改成「id 不等于自己」, 「只有更旧的 run」那条必须由红变绿 ⇒ 证明上面那条断言守得住比较方向。
sed 's/select(.id > \$me)/select(.id != $me)/' "$SUT" > "$T/mut.sh"
grep -q 'select(.id != $me)' "$T/mut.sh" || { echo "FAIL 变异没打进去"; fail=1; }
echo '{"workflow_id": 777}' > "$T/run.json"; echo '{"workflow_runs":[{"id":998},{"id":1000}]}' > "$T/runs.json"; rm -f "$T/api-fail"
if FAKE_DIR=$T CURL="$T/curl" EVENT_NAME=pull_request HEAD_SHA=abc GITHUB_RUN_ID=1000 GITHUB_REPOSITORY=o/r \
   bash "$T/mut.sh" fast-contracts cancelled >/dev/null 2>&1; then echo "PASS 变异 (id != 自己) 把真取消放行了 ⇒ 方向断言有效"
else echo "FAIL 变异没让真取消变绿, 方向断言没守住东西"; fail=1; fi

# 接线: 两个汇总作业都调脚本, 且都给了 actions: read。
for job in fast-contracts postgres-reversal-concurrency; do
  blk=$(awk -v j="  $job:" '$0==j{on=1;print;next} on&&/^  [a-z][a-z0-9-]*:$/{exit} on{print}' "$WF")
  echo "$blk" | grep -q "run: bash scripts/ci/java-gate-shard-summary.sh $job \"\$RESULT\"" \
    && echo "$blk" | grep -q 'actions: read' \
    && { echo "PASS 接线 $job"; n=$((n+1)); } || { echo "FAIL 接线 $job: 汇总作业没调判定脚本或缺 actions: read"; fail=1; }
done

echo "cases=$n fail=$fail"
[ "$n" -ge 11 ] || { echo "FAIL 用例数 $n < 11, 没跑完"; exit 1; }
exit $fail
