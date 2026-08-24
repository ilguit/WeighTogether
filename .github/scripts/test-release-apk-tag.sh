#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${script_dir}/release-apk-tag.sh"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_eq() {
    [[ "$1" == "$2" ]] || fail "expected '$2', got '$1'"
}

assert_workflow_order() {
    local workflow="$1"
    shift
    local previous_line=0
    local pattern line
    for pattern in "$@"; do
        line="$(grep -nF -- "$pattern" "$workflow" | head -n 1 | cut -d: -f1)"
        [[ -n "$line" ]] || fail "workflow is missing '$pattern'"
        ((line > previous_line)) || fail "workflow step '$pattern' is out of order"
        previous_line="$line"
    done
}

new_fixture() {
    local name="$1"
    local remote="${test_root}/${name}.git"
    local repo="${test_root}/${name}"
    git init --bare --quiet "$remote"
    git init --quiet -b main "$repo"
    git -C "$repo" config user.name "Release Test"
    git -C "$repo" config user.email "release-test@example.invalid"
    git -C "$repo" remote add origin "$remote"
    printf 'initial\n' >"${repo}/file.txt"
    git -C "$repo" add file.txt
    git -C "$repo" commit --quiet -m initial
    git -C "$repo" push --quiet -u origin main
    printf '%s\n' "$repo"
}

append_commit() {
    local repo="$1"
    local text="$2"
    printf '%s\n' "$text" >>"${repo}/file.txt"
    git -C "$repo" add file.txt
    git -C "$repo" commit --quiet -m "$text"
    git -C "$repo" rev-parse HEAD
}

run_script() {
    local repo="$1"
    shift
    git -C "$repo" config user.name "Release Test"
    git -C "$repo" config user.email "release-test@example.invalid"
    (cd "$repo" && "$script" "$@")
}

repo="$(new_fixture basic)"
sha="$(git -C "$repo" rev-parse HEAD)"
assert_eq "$(run_script "$repo" preflight 0.1.1 "$sha")" "null"
run_script "$repo" publish 0.1.1 "$sha"
assert_eq "$(git -C "$repo" cat-file -t apk/0.1.1)" "tag"
assert_eq "$(git -C "$repo" rev-parse apk/0.1.1^{commit})" "$sha"
assert_eq "$(git -C "$repo" ls-remote --tags origin refs/tags/apk/0.1.1^{} | awk '{print $1}')" "$sha"

# A same-version/same-SHA rerun is a no-op and retains the tag object.
tag_object="$(git -C "$repo" rev-parse apk/0.1.1)"
run_script "$repo" publish 0.1.1 "$sha"
assert_eq "$(git -C "$repo" rev-parse apk/0.1.1)" "$tag_object"

# The newest reachable version is selected; a tag on another branch is ignored.
next_sha="$(append_commit "$repo" next)"
git -C "$repo" push --quiet origin main
git -C "$repo" tag -a apk/0.1.2 "$next_sha" -m release
git -C "$repo" push --quiet origin refs/tags/apk/0.1.2
git -C "$repo" checkout --quiet -b unrelated "$sha"
unrelated_sha="$(append_commit "$repo" unrelated)"
git -C "$repo" tag -a apk/9.9.9 "$unrelated_sha" -m unrelated
git -C "$repo" push --quiet origin refs/tags/apk/9.9.9
git -C "$repo" checkout --quiet main
third_sha="$(append_commit "$repo" third)"
assert_eq "$(run_script "$repo" preflight 0.1.3 "$third_sha")" "apk/0.1.2"

# Reusing a released version for another commit fails clearly.
if run_script "$repo" preflight 0.1.2 "$third_sha" >"${test_root}/conflict.out" 2>&1; then
    fail "version conflict unexpectedly succeeded"
fi
grep -q "already released from" "${test_root}/conflict.out" || fail "missing conflict diagnostic"

# A lightweight release tag is invalid, even when it targets the requested SHA.
lightweight_repo="$(new_fixture lightweight)"
lightweight_sha="$(git -C "$lightweight_repo" rev-parse HEAD)"
git -C "$lightweight_repo" tag apk/0.1.9 "$lightweight_sha"
git -C "$lightweight_repo" push --quiet origin refs/tags/apk/0.1.9
if run_script "$lightweight_repo" preflight 0.1.9 "$lightweight_sha" >"${test_root}/lightweight.out" 2>&1; then
    fail "lightweight release tag unexpectedly succeeded"
fi
grep -q "not an annotated tag" "${test_root}/lightweight.out" || fail "missing lightweight-tag diagnostic"

# A local annotated tag left by a failure before push can be published by a rerun.
partial_repo="$(new_fixture partial)"
partial_sha="$(git -C "$partial_repo" rev-parse HEAD)"
git -C "$partial_repo" tag -a apk/0.2.0 "$partial_sha" -m partial
run_script "$partial_repo" publish 0.2.0 "$partial_sha"
assert_eq "$(git -C "$partial_repo" ls-remote --tags origin refs/tags/apk/0.2.0^{} | awk '{print $1}')" "$partial_sha"

# Simulate a competing publisher during push. Its tag targets the same commit;
# the rejected original push must be converted to an idempotent success.
race_repo="$(new_fixture race)"
race_sha="$(git -C "$race_repo" rev-parse HEAD)"
competitor="${test_root}/competitor"
git clone --quiet "${test_root}/race.git" "$competitor"
git -C "$competitor" config user.name "Competing Publisher"
git -C "$competitor" config user.email "competitor@example.invalid"
mkdir -p "${race_repo}/.git/hooks"
cat >"${race_repo}/.git/hooks/pre-push" <<HOOK
#!/usr/bin/env bash
set -euo pipefail
git -C "$competitor" tag -a apk/0.3.0 "$race_sha" -m competing
git -C "$competitor" push --quiet origin refs/tags/apk/0.3.0
exit 1
HOOK
chmod +x "${race_repo}/.git/hooks/pre-push"
run_script "$race_repo" publish 0.3.0 "$race_sha"
assert_eq "$(git -C "$race_repo" ls-remote --tags origin refs/tags/apk/0.3.0^{} | awk '{print $1}')" "$race_sha"

# Only the requested tag is pushed; an unrelated local tag stays local.
isolated_repo="$(new_fixture isolated)"
isolated_sha="$(git -C "$isolated_repo" rev-parse HEAD)"
git -C "$isolated_repo" tag -a local-only "$isolated_sha" -m local
run_script "$isolated_repo" publish 0.4.0 "$isolated_sha"
[[ -z "$(git -C "$isolated_repo" ls-remote --tags origin refs/tags/local-only)" ]] || fail "unrelated tag was pushed"

# Keep the workflow contract testable without executing GitHub Actions.
workflow="${script_dir}/../workflows/manual-personal-apk.yml"
grep -qF "contents: write" "$workflow" || fail "workflow lacks tag push permission"
grep -qF "fetch-depth: 0" "$workflow" || fail "workflow uses shallow checkout"
grep -qF "cancel-in-progress: false" "$workflow" || fail "workflow cancels an active release"
grep -qF 'previousReleaseTag:' "$workflow" || fail "artifact metadata lacks previousReleaseTag"
grep -qF 'artifact_path=${artifact_dir}' "$workflow" || fail "artifact does not include APK and metadata directory"
grep -qF 'GIT_COMMITTER_NAME: github-actions[bot]' "$workflow" || fail "workflow lacks an annotated-tag identity"
assert_workflow_order "$workflow" \
    "- name: Verify remote release tag" \
    "- name: Build personal debug APK" \
    "- name: Prepare APK artifact" \
    "- name: Upload personal APK" \
    "- name: Publish APK release tag"

echo "All release APK tag tests passed."
