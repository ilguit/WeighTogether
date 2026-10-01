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

assemble_graph="$(task_graph :app:assembleDebug)"
assert_contains "$assemble_graph" verifyDebugReleaseMetadata
assert_contains "$assemble_graph" packageDebug

package_graph="$(task_graph :app:packageDebug)"
assert_contains "$package_graph" verifyDebugReleaseMetadata

unit_graph="$(task_graph :app:testDebugUnitTest)"
assert_excludes "$unit_graph" verifyDebugReleaseMetadata

lint_graph="$(task_graph :app:lintDebug)"
assert_excludes "$lint_graph" verifyDebugReleaseMetadata

mode_init_script="$(mktemp)"
trap 'rm -f "$mode_init_script"' EXIT
cat >"$mode_init_script" <<'EOF'
gradle.projectsEvaluated {
    if (gradle.rootProject.name != "WeighTogether") {
        return
    }
    def app = gradle.rootProject.project(":app")
    app.tasks.register("assertReleaseHistoryModeWiring") {
        doLast {
            def expected = System.getenv("EXPECTED_RELEASE_HISTORY_MODE")
            def generate = app.tasks.named("generateDebugReleaseHistory").get()
            def verify = app.tasks.named("verifyDebugReleaseMetadata").get()
            if (generate.mode.get().id != expected) {
                throw new GradleException("generate mode ${generate.mode.get().id} != ${expected}")
            }
            if (verify.mode.get().id != expected) {
                throw new GradleException("verify mode ${verify.mode.get().id} != ${expected}")
            }
        }
    }
}
EOF

EXPECTED_RELEASE_HISTORY_MODE=build \
    "$gradle" -p "$repository_root" -I "$mode_init_script" :app:assertReleaseHistoryModeWiring --quiet
EXPECTED_RELEASE_HISTORY_MODE=release \
    "$gradle" -p "$repository_root" -I "$mode_init_script" :app:assertReleaseHistoryModeWiring \
    -PreleaseHistoryMode=release --quiet

invalid_mode_output="$(mktemp)"
trap 'rm -f "$mode_init_script" "$invalid_mode_output"' EXIT
if EXPECTED_RELEASE_HISTORY_MODE=invalid \
    "$gradle" -p "$repository_root" -I "$mode_init_script" :app:assertReleaseHistoryModeWiring \
    -PreleaseHistoryMode=invalid --quiet >"$invalid_mode_output" 2>&1; then
    fail "unknown releaseHistoryMode unexpectedly succeeded"
fi
grep -qF "Unknown release history mode 'invalid'; expected one of: build, release" "$invalid_mode_output" || \
    fail "unknown releaseHistoryMode did not report the supported values"

echo "All release metadata wiring tests passed."
