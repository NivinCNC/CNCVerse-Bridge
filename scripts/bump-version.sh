#!/usr/bin/env bash
# Bumps the release version in version.properties and stamps the changelog.
#
#   scripts/bump-version.sh [patch|minor|major]      (default: patch, 1.5.9 -> 1.5.10)
#
# - android.versionName and desktop.versionName both become the new version
# - android.versionCode goes up by one (Android only installs an APK with a higher code)
# - "## Unreleased" in CHANGELOG.md becomes "## <new version>"
# Prints the new version (without "v") on stdout. The release workflow runs this for every release.
set -euo pipefail
cd "$(dirname "$0")/.."

kind="${1:-patch}"
props=version.properties
cur=$(sed -n 's/^android\.versionName=//p' "$props" | tr -d '[:space:]')
code=$(sed -n 's/^android\.versionCode=//p' "$props" | tr -d '[:space:]')
[[ "$cur" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "bump-version: cannot read android.versionName from $props (got '$cur')" >&2; exit 1; }
[[ "$code" =~ ^[0-9]+$ ]] || { echo "bump-version: cannot read android.versionCode from $props (got '$code')" >&2; exit 1; }

IFS=. read -r major minor patch <<<"$cur"
case "$kind" in
  patch) patch=$((patch + 1)) ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  major) major=$((major + 1)); minor=0; patch=0 ;;
  *) echo "usage: $0 [patch|minor|major]" >&2; exit 2 ;;
esac
new="$major.$minor.$patch"

sed -i -E \
  -e "s/^android\.versionName=.*/android.versionName=$new/" \
  -e "s/^android\.versionCode=.*/android.versionCode=$((code + 1))/" \
  -e "s/^desktop\.versionName=.*/desktop.versionName=$new/" \
  "$props"

if grep -q '^## Unreleased' CHANGELOG.md; then
  sed -i -E "s/^## Unreleased.*/## $new/" CHANGELOG.md
fi
echo "$new"
