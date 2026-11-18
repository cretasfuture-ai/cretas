#!/usr/bin/env bash
# bundle-prs.sh 场景测试: 本地裸仓库当 origin (refs/pull/N/head), 假 gh 返回 PR 信息, 不碰 GitHub。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../bundle-prs.sh"
T=$(mktemp -d)
FAKE="$T/gh"
cat > "$FAKE" <<'EOF'
#!/usr/bin/env bash
S="$FAKE_GH_STATE"; echo "$*" >> "$S/calls"
case "$1 $2" in
  "pr view")
    n=$3
    case "$*" in
      *"state,body"*) cat "$S/pr$n.state"; echo; cat "$S/pr$n.body" 2>/dev/null;;
      *"state,headRefOid --jq"*) echo "$(cat "$S/pr$n.state") $(cat "$S/pr$n.sha")";;
      *) printf '%s\t%s\t%s\t%s\n' "$(cat "$S/pr$n.state")" "$(cat "$S/pr$n.sha")" "$(cat "$S/pr$n.cross" 2>/dev/null || echo false)" "title $n";;
    esac;;
  "pr checks") cat "$S/pr$3.red" 2>/dev/null; exit 0;;
  "pr create")
    while [ $# -gt 0 ]; do [ "$1" = --body-file ] && cp "$2" "$S/created.body"; shift; done
    echo "https://example/pull/900";;
  "pr close") echo "$3" >> "$S/closed";;
  *) echo "fake gh: unhandled $*" >&2; exit 1;;
esac
EOF
chmod +x "$FAKE"

pass=0; failn=0
ok() { pass=$((pass+1)); echo "PASS $1"; }
bad() { failn=$((failn+1)); echo "FAIL $1"; sed 's/^/    /' "$S/out" | tail -5; }
q() { git -c user.email=t@t -c user.name=t "$@"; }

# 一个 origin + 一个 organizer 克隆; PR 101/102/103 改不同文件, 104 与 101 冲突, 105 碰红线
setup() {
  S="$T/s$1"; rm -rf "$S"; mkdir -p "$S"; : > "$S/calls"
  q init -q --bare -b main "$S/origin.git"
  q clone -q "$S/origin.git" "$S/seed" 2>/dev/null
  (cd "$S/seed" && echo base > a.txt && echo base > b.txt && q add . && q commit -qm base && q push -q origin HEAD:main)
  mk() { # n file content
    (cd "$S/seed" && q checkout -q -B "pr$1" origin/main 2>/dev/null || q checkout -q -B "pr$1" main
     mkdir -p "$(dirname "$2")"; echo "$3" > "$2"; q add .; q commit -qm "change $1"
     q push -q origin "HEAD:refs/pull/$1/head"; q push -q origin "HEAD:refs/heads/lane$1")
    echo OPEN > "$S/pr$1.state"; git -C "$S/seed" rev-parse HEAD > "$S/pr$1.sha"
  }
  (cd "$S/seed" && q fetch -q origin)
  mk 101 a.txt one; mk 102 b.txt two; mk 103 c.txt three; mk 104 a.txt clash
  mk 105 backend/java/x/src/main/resources/db/migration/V9__x.sql "create table x();"
  q clone -q "$S/origin.git" "$S/org" 2>/dev/null
}
run() { (cd "$S/org" && FAKE_GH_STATE="$S" BUNDLE_GH="$FAKE" TMPDIR="$S" bash "$SCRIPT" "$@") > "$S/out" 2>&1; echo $? > "$S/rc"; }
rc() { cat "$S/rc"; }

# B1 三项无冲突 -> BUNDLED, 推了 bundle/ 分支, 包含三项提交, 描述带三条机读标记, 当前目录没被切分支
setup 1; before=$(git -C "$S/org" rev-parse HEAD); run create --name t1 --title T 101 102 103
bh=$(git -C "$S/origin.git" rev-parse refs/heads/bundle/t1 2>/dev/null)
[ "$(rc)" = 0 ] && grep -q 'RESULT=BUNDLED' "$S/out" && [ -n "$bh" ] \
  && git -C "$S/origin.git" merge-base --is-ancestor "$(cat "$S/pr101.sha")" "$bh" \
  && git -C "$S/origin.git" merge-base --is-ancestor "$(cat "$S/pr103.sha")" "$bh" \
  && [ "$(grep -c '^<!-- bundle-item: #10[123] [0-9a-f]\{40\} -->$' "$S/created.body")" = 3 ] \
  && [ "$(git -C "$S/org" rev-parse HEAD)" = "$before" ] && [ -z "$(git -C "$S/org" worktree list | sed 1d)" ] \
  && ok B1-bundle || bad B1-bundle

# B2 冲突 -> rc3 指名 #104 与冲突文件, 不推送
setup 2; run create --name t2 --title T 101 104
[ "$(rc)" = 3 ] && grep -q 'RESULT=CONFLICT item=#104 files=a.txt' "$S/out" && ! git -C "$S/origin.git" rev-parse -q --verify refs/heads/bundle/t2 >/dev/null && ok B2-conflict || bad B2-conflict

# B3 红线路径 -> REFUSED, 一项都不拼
setup 3; run create --name t3 --title T 101 105
[ "$(rc)" = 4 ] && grep -q 'RESULT=REFUSED.*#105:redline' "$S/out" && ! grep -q 'pr create' "$S/calls" && ok B3-redline-refused || bad B3-redline-refused

# B4 有失败检查 -> REFUSED
setup 4; echo java-gate > "$S/pr102.red"; run create --name t4 --title T 101 102
[ "$(rc)" = 4 ] && grep -q '#102:red(java-gate)' "$S/out" && ok B4-red-refused || bad B4-red-refused

# B5 原 PR 已关 / 来自 fork -> REFUSED
setup 5; echo CLOSED > "$S/pr101.state"; echo true > "$S/pr102.cross"; run create --name t5 --title T 101 102
[ "$(rc)" = 4 ] && grep -q '#101:state=CLOSED' "$S/out" && grep -q '#102:fork' "$S/out" && ok B5-state-fork-refused || bad B5-state-fork-refused

# B6 branch: 项 (执行线没开 PR) 与 PR 混拼; --dry-run 不推送不开 PR
setup 6; run create --name t6 --title T --dry-run 101 branch:lane103
[ "$(rc)" = 0 ] && grep -q 'RESULT=DRY_RUN_OK.*items=2' "$S/out" && grep -q 'bundle-item: branch:lane103' "$S/out" \
  && ! git -C "$S/origin.git" rev-parse -q --verify refs/heads/bundle/t6 >/dev/null && ! grep -q 'pr create' "$S/calls" && ok B6-branch-dryrun || bad B6-branch-dryrun

# B7 close: 集成 PR 已合并, #101 head 未变 -> 关; #102 拼完又推了 -> 不关报 DRIFT
setup 7; run create --name t7 --title T 101 102; cp "$S/created.body" "$S/pr900.body"; echo MERGED > "$S/pr900.state"
echo 0000000000000000000000000000000000000002 > "$S/pr102.sha"; run close 900
[ "$(rc)" = 1 ] && grep -q 'RESULT=DRIFT closed=\[#101\] not-closed=\[#102\]' "$S/out" && [ "$(cat "$S/closed")" = 101 ] && ok B7-close-drift || bad B7-close-drift

# B8 close: 集成 PR 还没合并 -> 拒绝, 一个都不关
setup 8; run create --name t8 --title T 101 102; cp "$S/created.body" "$S/pr900.body"; echo OPEN > "$S/pr900.state"; run close 900
[ "$(rc)" = 2 ] && [ ! -f "$S/closed" ] && ok B8-close-requires-merged || bad B8-close-requires-merged

# B9 close 全部 head 未变 -> CLOSED rc0
setup 9; run create --name t9 --title T 101 102 103; cp "$S/created.body" "$S/pr900.body"; echo MERGED > "$S/pr900.state"; run close 900
[ "$(rc)" = 0 ] && grep -q 'RESULT=CLOSED bundle=#900 closed=\[#101 #102 #103\]' "$S/out" && ok B9-close-all || bad B9-close-all

rm -rf "$T"
echo "TOTAL=$((pass+failn)) PASS=$pass FAIL=$failn"
[ "$failn" = 0 ]
