#!/bin/bash
# Shared by the hosted router and macOS Bash 3.2 refill. Output: queued busy.
# Validate every page, including the API totals (list-runs filters can cap results).
mini_queue_snapshot() {
  local repo="$1" exclude="$2" workflows="$3" labels="$4"
  local status pages ids all_ids= id counts queued=0 busy=0
  local run_filter job_filter
  run_filter='("total " + (.total_count | tostring)), (.workflow_runs[] | [.id, (.path | split("/") | last)] | join(" "))'
  case "$labels" in ''|*[!a-zA-Z0-9_\ -]*) return 1 ;; esac
  job_filter='("total " + (.total_count | tostring)), (.jobs[] |'
  job_filter="$job_filter (\"$labels\" | split(\" \")) as \$mini | "'(.labels // []) as $labels
    | [.id, .status, any($labels[]; . as $tag | $mini | index($tag) != null)] | map(tostring) | join(" "))'

  for status in queued in_progress; do
    pages=$("${GH:-gh}" api --paginate "repos/$repo/actions/runs?status=$status&per_page=100" -q "$run_filter") || return 1
    pages=${pages//$'\r'/}
    ids=$(awk -v workflows="$workflows" '
      BEGIN { n = split(workflows, names, " "); for (i = 1; i <= n; i++) allowed[names[i]] = 1 }
      $1 == "total" && NF == 2 && $2 ~ /^[0-9]+$/ {
        if (headers++ && total != $2) bad = 1
        total = $2; next
      }
      NF == 2 && $1 ~ /^[1-9][0-9]*$/ {
        if (seen[$1]++) bad = 1
        count++; if ($2 in allowed) print $1; next
      }
      { bad = 1 }
      END { if (bad || !headers || total >= 1000 || count + 0 != total) exit 1 }
    ' <<< "$pages") || return 1
    all_ids="$all_ids $ids"
  done

  local seen=' '
  for id in $all_ids; do
    [ "$id" = "$exclude" ] && continue
    case "$seen" in *" $id "*) continue ;; esac
    seen="$seen$id "
    pages=$("${GH:-gh}" api --paginate "repos/$repo/actions/runs/$id/jobs?filter=latest&per_page=100" -q "$job_filter") || return 1
    pages=${pages//$'\r'/}
    counts=$(awk '
      $1 == "total" && NF == 2 && $2 ~ /^[0-9]+$/ {
        if (headers++ && total != $2) bad = 1
        total = $2; next
      }
      NF == 3 && $1 ~ /^[1-9][0-9]*$/ && $2 ~ /^[a-z_]+$/ && $3 ~ /^(true|false)$/ {
        if (seen[$1]++) bad = 1
        count++
        if ($3 == "true") {
          if ($2 == "queued") queued++
          if ($2 == "in_progress") busy++
        }
        next
      }
      { bad = 1 }
      END { if (bad || !headers || count + 0 != total) exit 1; print queued + 0, busy + 0 }
    ' <<< "$pages") || return 1
    queued=$((queued + ${counts% *}))
    busy=$((busy + ${counts#* }))
  done
  printf '%s %s\n' "$queued" "$busy"
}
