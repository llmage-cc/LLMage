#!/usr/bin/env bash
# Card-grid hover preview gate (every card shown in a search /
# select / reveal popup needs the same hover large-card preview as board cards).
#
#   bash scripts/verify-card-grid-preview.sh
#
# Handler-binding grep in the verify-explain-coverage pattern: it is impossible
# to assert onMouseEnter via react-dom/server (React strips event handlers from
# SSR markup), so this gate greps the REAL render sites:
#   1. every component that renders a popup-card tile must wire the shared
#      `useCardGridPreview` hook (showPreview / hidePreview),
#   2. every popup-card tile must carry onMouseEnter -> showPreview and
#      onMouseLeave -> hidePreview within its own opening JSX tag.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
fail() { echo "FAIL: $*"; fail=1; }

# ── 1. every popup-cards-grid owner wires the hook ──
declare -A GRID_OWNERS=(
  [src/components/CardSelectPopup.tsx]=1
  [src/components/RevealedPopup.tsx]=1
  [src/components/ZonePopup.tsx]=1
  [src/components/prompts/TargetPrompt.tsx]=1
  [src/components/prompts/ChoosePilePrompt.tsx]=1
)
for f in "${!GRID_OWNERS[@]}"; do
  grep -q 'useCardGridPreview' "$f" || fail "$f renders popup cards but never imports/uses useCardGridPreview"
  grep -q 'const { showPreview, hidePreview } = useCardGridPreview()' "$f" || fail "$f does not destructure { showPreview, hidePreview } from useCardGridPreview"
done
echo "checked ${#GRID_OWNERS[@]} popup-grid owners for hook wiring"

# ── 2. every popup-card tile binds showPreview / hidePreview ──
# awk walks each component, collects one JSX opening tag (from a line whose
# className matches a popup-card TILE class — popup-card, popup-card-thumb,
# popup-card-mini; inner popup-card-name/-type/-validity label divs are
# excluded), accumulating lines until the tag closes with '>', then asserts
# both handler bindings inside that tag.
for f in "${!GRID_OWNERS[@]}"; do
  awk -v file="$f" '
    /className=.*popup-card-(name|type|validity)/ { next }
    /className=.*popup-cards-grid/ { next }   # grid container, not a tile
    /className=.*popup-card/ { intag=1; tag="" }
    intag {
      tag = tag $0 "\n"
      if (/>[ \t]*$/) {
        if (tag !~ /onMouseEnter=\{\(e\) => showPreview\(/)
          { print "FAIL: " file ":" FNR " popup-card tile has no onMouseEnter showPreview binding"; bad=1 }
        else if (tag !~ /onMouseLeave=\{hidePreview\}/)
          { print "FAIL: " file ":" FNR " popup-card tile has no onMouseLeave hidePreview binding"; bad=1 }
        intag=0; tag=""
      }
    }
    END { exit bad ? 1 : 0 }
  ' "$f" || fail=1
done
echo "checked popup-card tiles bind showPreview/hidePreview"
exit $fail
