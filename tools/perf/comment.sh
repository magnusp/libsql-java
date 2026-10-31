#!/usr/bin/env bash
# Create or update the sticky performance comment on a pull request.
#
# usage: comment.sh <owner/repo> <pr-number> <comment-file>
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <owner/repo> <pr-number> <comment-file>" >&2
    exit 2
fi

repo=$1
pr=$2
body_file=$3
marker='<!-- libsql-perf -->'

if [[ ! -f "$body_file" ]]; then
    echo "comment file not found: $body_file" >&2
    exit 2
fi

existing=$(gh api --paginate "repos/${repo}/issues/${pr}/comments" \
    --jq ".[] | select(.body | startswith(\"${marker}\")) | .id" | head -n 1 || true)

payload=$(mktemp)
trap 'rm -f "$payload"' EXIT
jq -Rs '{body: .}' "$body_file" >"$payload"

if [[ -n "$existing" ]]; then
    gh api --method PATCH "repos/${repo}/issues/comments/${existing}" --input "$payload" >/dev/null
    echo "Updated performance comment ${existing} on #${pr}"
else
    gh api --method POST "repos/${repo}/issues/${pr}/comments" --input "$payload" >/dev/null
    echo "Created performance comment on #${pr}"
fi
