#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradle="${repository_root}/gradlew"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

task_graph() {
    "$gradle" -p "$repository_root" "$1" --dry-run
}

assert_contains() {
    local output="$1"
    local task="$2"
    grep -qF ":app:${task} SKIPPED" <<<"$output" || fail "task graph does not contain :app:${task}"
}

assert_excludes() {
    local output="$1"
    local task="$2"
    if grep -qF ":app:${task} SKIPPED" <<<"$output"; then
        fail "task graph unexpectedly contains :app:${task}"
    fi
}

assemble_graph="$(task_graph :app:assemblePersonalDebug)"
assert_contains "$assemble_graph" verifyPersonalDebugReleaseMetadata
assert_contains "$assemble_graph" packagePersonalDebug

package_graph="$(task_graph :app:packagePersonalDebug)"
assert_contains "$package_graph" verifyPersonalDebugReleaseMetadata

unit_graph="$(task_graph :app:testPersonalDebugUnitTest)"
assert_excludes "$unit_graph" verifyPersonalDebugReleaseMetadata

lint_graph="$(task_graph :app:lintPersonalDebug)"
assert_excludes "$lint_graph" verifyPersonalDebugReleaseMetadata

echo "All release metadata wiring tests passed."
