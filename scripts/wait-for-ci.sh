#!/usr/bin/env bash
# Waits for the CI run of a specific commit and reports its result.
#
# `gh run list --limit 1` immediately after a push is a race: the new run may
# not exist yet, so it returns the *previous* one. That is how a red commit was
# reported as green on 2026-08-11 — the check passed, but it had watched the
# wrong run. Matching on the commit SHA removes the race entirely.
#
# Usage: scripts/wait-for-ci.sh [commit-sha]   (defaults to HEAD)
set -euo pipefail

sha="${1:-$(git rev-parse HEAD)}"
deadline=$((SECONDS + ${TIMEOUT_SECONDS:-1200}))

echo "waiting for a CI run of ${sha:0:8}"
run_id=""
while [ -z "$run_id" ]; do
    run_id=$(gh run list --limit 20 --json databaseId,headSha \
        --jq "[.[] | select(.headSha == \"$sha\")][0].databaseId // empty")
    [ -n "$run_id" ] && break
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "no run appeared for ${sha:0:8} within the timeout" >&2
        exit 2
    fi
    sleep 5
done

echo "watching run $run_id"
gh run watch "$run_id" --exit-status >/dev/null
echo "CI green for ${sha:0:8}"
