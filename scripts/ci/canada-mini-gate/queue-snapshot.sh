#!/bin/bash
# Shared by the hosted router and macOS Bash 3.2 refill. Output: queued busy.
# Validate every page, including the API totals (list-runs filters can cap results).

# Result and budget belong to the caller. Each gh invocation reads exactly one page.
mini_queue_pages() {
  local endpoint="$1" filter="$2" page=1 page_count=1 records header total extra
  pages=
  while [ "$page" -le "$page_count" ]; do
    [ "$requests_left" -gt 0 ] || return 1
    requests_left=$((requests_left - 1))
    records=$("${GH:-gh}" api "$endpoint&per_page=100&page=$page" -q "$filter") || return 1
    records=${records//$'\r'/}
    if [ "$page" -eq 1 ]; then
      read -r header total extra <<< "$records"
      [ "$header" = total ] && [ -z "$extra" ] || return 1
      [[ "$total" =~ ^(0|[1-9][0-9]{0,8})$ ]] || return 1
      page_count=$(( (total + 99) / 100 ))
      if [ "$((page_count - 1))" -gt "$requests_left" ]; then
        echo "mini queue snapshot exceeds REST page budget" >&2
        return 1
      fi
      pages="$records"
    else
      pages="$pages"$'\n'"$records"
    fi
    page=$((page + 1))
  done
}

mini_queue_snapshot() {
  local repo="$1" exclude="$2" workflows="$3" labels="$4"
  local status pages ids all_ids= id counts queued=0 busy=0
  # Shared cache at one refresh per 20 s: 180 × 16 = 2,880 REST reads/hour.
  # With one page per endpoint and 12 active runs: 180 × (2 + 12) = 2,520/hour.
  local active_limit=12 requests_left=16
  local run_filter job_filter
  run_filter='("total " + (.total_count | tostring)), (.workflow_runs[] | [.id, (.path | split("/") | last)] | join(" "))'
  case "$labels" in ''|*[!a-zA-Z0-9_\ -]*) return 1 ;; esac
  job_filter='("total " + (.total_count | tostring)), (.jobs[] |'
  job_filter="$job_filter (\"$labels\" | split(\" \")) as \$mini | "'(.labels // []) as $labels
    | [.id, .status, any($labels[]; . as $tag | $mini | index($tag) != null)] | map(tostring) | join(" "))'

  for status in queued in_progress; do
    mini_queue_pages "repos/$repo/actions/runs?status=$status" "$run_filter" || return 1
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

  local seen=' ' active_ids= active_count=0
  for id in $all_ids; do
    [ "$id" = "$exclude" ] && continue
    case "$seen" in *" $id "*) continue ;; esac
    seen="$seen$id "
    active_count=$((active_count + 1))
    active_ids="$active_ids $id"
  done
  if [ "$active_count" -gt "$active_limit" ]; then
    echo "mini queue snapshot has $active_count active runs (limit $active_limit)" >&2
    return 1
  fi
  for id in $active_ids; do
    mini_queue_pages "repos/$repo/actions/runs/$id/jobs?filter=latest" "$job_filter" || return 1
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
