#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
output_dir="${1:-$script_dir/dist}"

mkdir -p "$output_dir"
for skill in project-hub wiki-ingest http-log-read; do
  archive="$output_dir/$skill.zip"
  entries=("$skill/SKILL.md" "$skill/lingxi.json" "$skill/assets" "$skill/scripts")
  if [[ -d "$script_dir/$skill/references" ]]; then
    entries+=("$skill/references")
  fi
  rm -f "$archive"
  (
    cd "$script_dir"
    zip -qr "$archive" "${entries[@]}"
  )
  echo "created $archive"
done
