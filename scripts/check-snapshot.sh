#!/usr/bin/env bash
# check-snapshot.sh — B8：docs/promql 参考快照 vs 上游 tag 的漂移检测。
#
# 用法：
#   bash scripts/check-snapshot.sh [tag] [scope]
#     tag   默认 v3.14.0（当前锚点，见 PORTING.md §0）
#     scope 默认 ported：parser/ + durations.go + value.go（PORTING.md §1 移植范围）
#           all  ：整个 docs/promql（快照取自发布后 main，非移植文件与 tag
#                   存在预期差异，漂移仅供参考）
#
# 行为：逐文件 curl raw.githubusercontent.com 上游 tag 版本，与本地快照 diff。
# 退出码：0 = 无漂移；1 = 有漂移/上游缺文件；2 = 用法/环境错误。
#
# 迭代场景（PORTING.md §0）：上游发新版本时
#   bash scripts/check-snapshot.sh v3.15.0
# 输出的 DRIFT 清单即本次升级需要核对的上游文件。
set -euo pipefail

TAG="${1:-v3.14.0}"
SCOPE="${2:-ported}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SNAP="$ROOT/docs/promql"
BASE="https://raw.githubusercontent.com/prometheus/prometheus/$TAG/promql"

command -v curl >/dev/null 2>&1 || { echo "需要 curl" >&2; exit 2; }

case "$SCOPE" in
  ported) PATTERNS=(parser durations.go value.go) ;;
  all)    PATTERNS=(.) ;;
  *) echo "未知 scope: $SCOPE（可选 ported|all）" >&2; exit 2 ;;
esac

files=()
for p in "${PATTERNS[@]}"; do
  while IFS= read -r f; do files+=("$f"); done < <(find "$SNAP/$p" -type f | sort)
done
[ "${#files[@]}" -gt 0 ] || { echo "快照目录为空: $SNAP" >&2; exit 2; }

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

drift=0
for f in "${files[@]}"; do
  rel="${f#"$SNAP"/}"
  if ! curl -fsSL "$BASE/$rel" -o "$tmp" 2>/dev/null; then
    echo "MISSING  $rel（上游 $TAG 无此文件）"
    drift=1
    continue
  fi
  if ! diff -q "$f" "$tmp" >/dev/null; then
    echo "DRIFT    $rel"
    drift=1
  fi
done

if [ "$drift" -eq 0 ]; then
  echo "OK: ${#files[@]} 个文件与 $TAG 一致（scope=$SCOPE）"
fi
exit $drift
