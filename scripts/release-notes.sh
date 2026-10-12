#!/usr/bin/env bash
# Writes the release notes to stdout.
#
#   scripts/release-notes.sh <tag> <repo> [previous-tag] [ref]
#
# e.g. scripts/release-notes.sh v1.5.10 khalsatorrent-droid/CNCVerse-Bridge v1.5.9 HEAD
#
# The notes are the "## <version>" section of CHANGELOG.md (what changed, in words), then the commit
# list since the previous tag, the download list and the disclaimer. The app shows everything before
# "## Downloads" in its update dialog.
set -euo pipefail
cd "$(dirname "$0")/.."

tag="${1:?tag (e.g. v1.5.10)}"
repo="${2:?repo (owner/name)}"
prev="${3:-}"
ref="${4:-HEAD}"
version="${tag#v}"

# The changelog section of this version: from "## <version>" up to the next "## "
section=$(awk -v v="$version" '
  $0 ~ "^## " && found { exit }
  $0 == "## " v { found = 1; next }
  found { print }
' CHANGELOG.md | sed -e '/./,$!d')

if [ -n "$section" ]; then
  echo "## What's new in ${version}"
  echo
  echo "$section"
  echo
fi

range="${prev:+$prev..}$ref"
commits=$(git log --no-merges --pretty='- %s (%h)' "$range" 2>/dev/null | grep -v '^- Release v[0-9]' || true)
if [ -n "$commits" ]; then
  echo "## Commits${prev:+ since $prev}"
  echo "$commits"
  echo
  [ -n "$prev" ] && { echo "**Full diff:** https://github.com/${repo}/compare/${prev}...${tag}"; echo; }
fi

echo "> **Disclaimer:** this app is vibe coded (written with AI assistance) and provided as is, with no warranty. The developer takes no responsibility for any damage, data loss or other consequences of using it. Use it at your own risk."
echo
echo "## Downloads"
echo "- **Android:** \`CNCVerse-Bridge-${tag}.apk\`"
echo "- **Windows:** \`CNCVerse-Bridge-${tag}.msi\` (or \`CNCVerse-Bridge-${tag}-setup.exe\`)"
echo "- **Linux (Debian/Ubuntu):** \`CNCVerse-Bridge-${tag}_amd64.deb\`"
