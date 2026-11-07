#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <sequence/mav0> <output-dir> [max-frames]" >&2
  exit 2
fi
MAV0=$(realpath "$1")
OUT=$(mkdir -p "$2" && realpath "$2")
MAX_ARGS=()
if [[ $# -eq 3 ]]; then MAX_ARGS=(--max-frames "$3"); fi
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
BUILD="$OUT/build"
mkdir -p "$BUILD"
python3 "$ROOT/tools/datasets/euroc_to_replay.py" "$MAV0" "$OUT/replay" "${MAX_ARGS[@]}"
c++ -std=c++17 -O3 -DNDEBUG -Wall -Wextra -Wpedantic -I"$ROOT/core/include" \
  "$ROOT"/core/src/*.cpp "$ROOT/tools/replay/main.cpp" -pthread -o "$BUILD/arkernel_replay"
c++ -std=c++17 -O3 -DNDEBUG -Wall -Wextra -Wpedantic -I"$ROOT/core/include" \
  "$ROOT"/core/src/*.cpp "$ROOT/tools/evaluation/main.cpp" -pthread -o "$BUILD/arkernel_trajectory_eval"
"$BUILD/arkernel_replay" "$OUT/replay/manifest.arkr" \
  --trajectory "$OUT/estimated.arktraj" | tee "$OUT/replay_metrics.json"
GROUND_TRUTH="$MAV0/state_groundtruth_estimate0/data.csv"
if [[ ! -f "$GROUND_TRUTH" ]]; then
  echo "Ground truth missing: $GROUND_TRUTH" >&2
  exit 3
fi
"$BUILD/arkernel_trajectory_eval" "$OUT/estimated.arktraj" "$GROUND_TRUTH" se3 \
  | tee "$OUT/evaluation_se3.json"
"$BUILD/arkernel_trajectory_eval" "$OUT/estimated.arktraj" "$GROUND_TRUTH" sim3 \
  | tee "$OUT/evaluation_sim3.json"
echo "Baseline artifacts written to $OUT"
