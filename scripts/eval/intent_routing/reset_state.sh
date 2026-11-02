#!/usr/bin/env bash
# Reset per-run mutable state in the eval DB so every pass starts from the same snapshot.
#
# The intent pipeline learns expressions on the fly (ai_learned_expressions) and records match
# history; left in place, rep 2 of the same input takes a learned fast path and the eval measures
# its own previous run. Only rows for the eval factory are touched.
#
# --tool-embeddings additionally wipes tool_embeddings. ToolRouterServiceImpl re-encodes a tool
# only when its row is missing or its vector is null — it never re-encodes on a description
# change — so after editing any Java tool description the round procedure is:
#   rebuild jar -> reset_state.sh --tool-embeddings -> restart Java (re-encodes at startup) -> run.
set -euo pipefail
CONTAINER="${EVAL_DB_CONTAINER:-cretas-eval-db}"
FACTORY="${EVAL_FACTORY:-EVAL01}"
WIPE_TOOL_EMB=0
for a in "$@"; do
  case "$a" in
    --tool-embeddings) WIPE_TOOL_EMB=1 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done
docker exec "$CONTAINER" psql -U cretas_user -d cretas_db -v ON_ERROR_STOP=1 -Atc "
  delete from ai_learned_expressions where factory_id = '$FACTORY';
  delete from intent_match_records where factory_id = '$FACTORY';
  delete from ai_training_samples where factory_id = '$FACTORY';
  select 'learned_left=' || count(*) from ai_learned_expressions where factory_id = '$FACTORY';
"
if [ "$WIPE_TOOL_EMB" = 1 ]; then
  docker exec "$CONTAINER" psql -U cretas_user -d cretas_db -v ON_ERROR_STOP=1 -Atc "
    delete from tool_embeddings;
    select 'tool_embeddings_left=' || count(*) from tool_embeddings;
  "
fi
