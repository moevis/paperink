#!/usr/bin/env bash
#
# 卡住「意大利面」。
#
# 拆分这件事靠自觉是守不住的——今天拆干净了，明天改着改着又会长回一个 900 行的类。
# 所以把它做成一道闸：超线的文件直接让流水线红掉，逼着人把它拆开，
# 而不是在一个越堆越大的类里再加一段。
#
# 用法：
#   scripts/check-size.sh            # 用默认上限 450
#   scripts/check-size.sh 300        # 临时收紧
#
set -euo pipefail

MAX="${1:-450}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src"

if [ ! -d "$SRC" ]; then
  echo "找不到源码目录：$SRC" >&2
  exit 2
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# 每个 .kt 的行数，倒序。wc 批量跑会多一行 "total"，滤掉。
find "$SRC" -name '*.kt' -type f -exec wc -l {} + | grep -v ' total$' | sort -rn > "$TMP" || true

echo "== 单文件行数上限：$MAX =="

OVER=0
while read -r n f; do
  [ -n "${n:-}" ] || continue
  mark=""
  if [ "$n" -gt "$MAX" ]; then
    mark="   ← 超了"
    OVER=$((OVER + 1))
  fi
  printf '%6d  %s%s\n' "$n" "${f#"$ROOT"/}" "$mark"
done < "$TMP"

if [ "$OVER" -gt 0 ]; then
  echo ""
  echo "有 $OVER 个文件超过 $MAX 行。拆开：按职责分文件，别在同一个类里越堆越长。" >&2
  exit 1
fi

echo "全部通过。"
