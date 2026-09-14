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

# Repeated ordinary builds can read history but never create or publish a tag.
build_repo="$(new_fixture build-only)"
build_sha="$(git -C "$build_repo" rev-parse HEAD)"
assert_eq "$(run_script "$build_repo" previous 0.1.1 "$build_sha")" "null"
assert_eq "$(run_script "$build_repo" previous 0.1.1 "$build_sha")" "null"
[[ -z "$(git -C "$build_repo" tag --list 'apk/*')" ]] || fail "build lookup created a local tag"
[[ -z "$(git -C "$build_repo" ls-remote --tags origin 'refs/tags/apk/*')" ]] || fail "build lookup published a tag"

repo="$(new_fixture basic)"
sha="$(git -C "$repo" rev-parse HEAD)"
assert_eq "$(run_script "$repo" preflight 0.1.1 "$sha")" "null"
run_script "$repo" publish 0.1.1 "$sha"
assert_eq "$(git -C "$repo" cat-file -t apk/0.1.1)" "tag"
assert_eq "$(git -C "$repo" rev-parse apk/0.1.1^{commit})" "$sha"
assert_eq "$(git -C "$repo" ls-remote --tags origin refs/tags/apk/0.1.1^{} | awk '{print $1}')" "$sha"

# Build-mode lookup never validates or changes the current-version tag and
# reports it as the latest reachable release boundary.
tag_object="$(git -C "$repo" rev-parse apk/0.1.1)"
assert_eq "$(run_script "$repo" previous 0.1.1 "$sha")" "apk/0.1.1"
assert_eq "$(git -C "$repo" rev-parse apk/0.1.1)" "$tag_object"

# A same-version/same-SHA rerun is a no-op and retains the tag object.
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

# A release introduced by the second parent replaces its older ancestor release.
merge_repo="$(new_fixture merged-side)"
merge_base="$(git -C "$merge_repo" rev-parse HEAD)"
git -C "$merge_repo" tag -a apk/0.5.0 "$merge_base" -m first-parent
git -C "$merge_repo" push --quiet origin refs/tags/apk/0.5.0
git -C "$merge_repo" branch side
append_commit "$merge_repo" main >/dev/null
git -C "$merge_repo" checkout --quiet side
printf 'side\n' >"${merge_repo}/side.txt"
git -C "$merge_repo" add side.txt
git -C "$merge_repo" commit --quiet -m side
side_sha="$(git -C "$merge_repo" rev-parse HEAD)"
git -C "$merge_repo" tag -a apk/0.6 "$side_sha" -m side
git -C "$merge_repo" push --quiet origin refs/tags/apk/0.6
git -C "$merge_repo" checkout --quiet main
git -C "$merge_repo" merge --quiet --no-ff side -m "Merge side"
merge_head="$(git -C "$merge_repo" rev-parse HEAD)"
assert_eq "$(run_script "$merge_repo" preflight 0.7 "$merge_head")" "apk/0.6"

# A release reached only through the second parent is still a release boundary.
side_only_repo="$(new_fixture side-only)"
git -C "$side_only_repo" branch side
append_commit "$side_only_repo" main >/dev/null
git -C "$side_only_repo" checkout --quiet side
printf 'side\n' >"${side_only_repo}/side.txt"
git -C "$side_only_repo" add side.txt
git -C "$side_only_repo" commit --quiet -m side
side_only_sha="$(git -C "$side_only_repo" rev-parse HEAD)"
git -C "$side_only_repo" tag -a apk/7.7.7 "$side_only_sha" -m side
git -C "$side_only_repo" push --quiet origin refs/tags/apk/7.7.7
git -C "$side_only_repo" checkout --quiet main
git -C "$side_only_repo" merge --quiet --no-ff side -m "Merge side"
side_only_head="$(git -C "$side_only_repo" rev-parse HEAD)"
assert_eq "$(run_script "$side_only_repo" preflight 7.7.8 "$side_only_head")" "apk/7.7.7"

# Reproduce an old task branch updated from main after multiple releases.
old_repo="$(new_fixture old-task)"
git -C "$old_repo" branch old-task
old_base="$(git -C "$old_repo" rev-parse HEAD)"
git -C "$old_repo" tag -a apk/0.2 "$old_base" -m release
release_sha="$(append_commit "$old_repo" release)"
git -C "$old_repo" tag -a apk/0.3 "$release_sha" -m release
next_release_sha="$(append_commit "$old_repo" next-release)"
git -C "$old_repo" tag -a apk/0.10 "$next_release_sha" -m release
git -C "$old_repo" push --quiet origin main --tags
git -C "$old_repo" checkout --quiet old-task
printf 'task\n' >"${old_repo}/task.txt"
git -C "$old_repo" add task.txt
git -C "$old_repo" commit --quiet -m task
git -C "$old_repo" merge --quiet --no-ff main -m "Update old task"
old_head="$(git -C "$old_repo" rev-parse HEAD)"
assert_eq "$(run_script "$old_repo" previous 0.10 "$old_head")" "apk/0.10"
assert_eq "$(run_script "$old_repo" previous 0.10 "$old_head")" "apk/0.10"
assert_eq "$(run_script "$old_repo" preflight 0.11 "$old_head")" "apk/0.10"
if run_script "$old_repo" preflight 0.10.0 "$old_head" >"${test_root}/equal.out" 2>&1; then
    fail "equal numeric candidate version unexpectedly succeeded"
fi
grep -q 'must be greater' "${test_root}/equal.out" || fail "missing numeric equality diagnostic"

# Publishing a second version on an existing boundary must not create ambiguity.
for mode in preflight publish; do
    if run_script "$old_repo" "$mode" 0.11 "$next_release_sha" >"${test_root}/retag.out" 2>&1; then
        fail "$mode accepted a second version at one commit"
    fi
    grep -q 'already tagged as' "${test_root}/retag.out" || fail "missing retag diagnostic"
done
[[ -z "$(git -C "$old_repo" ls-remote --tags origin refs/tags/apk/0.11)" ]] || fail "invalid tag published"

# A shallow clone cannot silently lose older boundaries.
shallow_repo="${test_root}/shallow"
git clone --quiet --depth 1 --branch main "file://${test_root}/old-task.git" "$shallow_repo"
shallow_head="$(git -C "$shallow_repo" rev-parse HEAD)"
if run_script "$shallow_repo" previous 0.10 "$shallow_head" >"${test_root}/shallow.out" 2>&1; then
    fail "shallow history unexpectedly succeeded"
fi
grep -q 'full Git history is required' "${test_root}/shallow.out" || fail "missing shallow diagnostic"

# Multiple names for one commit are ambiguous, including an idempotent rerun.
git -C "$old_repo" tag -a apk/0.10.1 "$next_release_sha" -m duplicate
git -C "$old_repo" push --quiet origin refs/tags/apk/0.10.1
for mode in previous preflight publish; do
    if run_script "$old_repo" "$mode" 0.10 "$next_release_sha" >"${test_root}/duplicate.out" 2>&1; then
        fail "$mode accepted duplicate release boundaries"
    fi
    grep -q 'multiple annotated APK tags' "${test_root}/duplicate.out" || fail "missing duplicate diagnostic"
done

# Two merged releases without an ancestor relationship cannot be ordered.
ambiguous_repo="$(new_fixture ambiguous)"
git -C "$ambiguous_repo" branch side
main_release="$(append_commit "$ambiguous_repo" main)"
git -C "$ambiguous_repo" tag -a apk/0.2 "$main_release" -m main
git -C "$ambiguous_repo" checkout --quiet side
printf 'side\n' >"${ambiguous_repo}/side.txt"
git -C "$ambiguous_repo" add side.txt
git -C "$ambiguous_repo" commit --quiet -m side
git -C "$ambiguous_repo" tag -a apk/0.3 -m side
git -C "$ambiguous_repo" checkout --quiet main
git -C "$ambiguous_repo" merge --quiet --no-ff side -m merge
git -C "$ambiguous_repo" push --quiet origin main --tags
ambiguous_head="$(git -C "$ambiguous_repo" rev-parse HEAD)"
if run_script "$ambiguous_repo" previous 0.4 "$ambiguous_head" >"${test_root}/ambiguous.out" 2>&1; then
    fail "incomparable releases unexpectedly succeeded"
fi
grep -q 'incomparable by ancestry' "${test_root}/ambiguous.out" || fail "missing ambiguity diagnostic"

# Numeric comparisons, including zero padding, must agree with the generator.
for versions in '0.10 0.9' '0.3 0.3.0' '0.03 0.3'; do
    read -r older_version newer_version <<<"$versions"
    order_repo="$(new_fixture "order-${older_version}-${newer_version}")"
    git -C "$order_repo" tag -a "apk/$older_version" -m older
    order_head="$(append_commit "$order_repo" newer)"
    git -C "$order_repo" tag -a "apk/$newer_version" -m newer
    git -C "$order_repo" push --quiet origin main --tags
    if run_script "$order_repo" previous 0.11 "$order_head" >"${test_root}/order.out" 2>&1; then
        fail "non-increasing versions $versions unexpectedly succeeded"
    fi
    grep -q 'versions must increase' "${test_root}/order.out" || fail "missing version order diagnostic"
done

# Reusing a released version for another commit fails clearly.
if run_script "$repo" preflight 0.1.2 "$third_sha" >"${test_root}/conflict.out" 2>&1; then
    fail "version conflict unexpectedly succeeded"
fi
grep -q "already released from" "${test_root}/conflict.out" || fail "missing conflict diagnostic"
assert_eq "$(run_script "$repo" previous 0.1.2 "$third_sha")" "apk/0.1.2"

# A lightweight release tag is invalid, even when it targets the requested SHA.
lightweight_repo="$(new_fixture lightweight)"
lightweight_sha="$(git -C "$lightweight_repo" rev-parse HEAD)"
git -C "$lightweight_repo" tag apk/0.1.9 "$lightweight_sha"
git -C "$lightweight_repo" push --quiet origin refs/tags/apk/0.1.9
if run_script "$lightweight_repo" preflight 0.1.9 "$lightweight_sha" >"${test_root}/lightweight.out" 2>&1; then
    fail "lightweight release tag unexpectedly succeeded"
fi
grep -q "not an annotated tag" "${test_root}/lightweight.out" || fail "missing lightweight-tag diagnostic"
assert_eq "$(run_script "$lightweight_repo" previous 0.1.9 "$lightweight_sha")" "null"

# A local annotated tag left by a failure before push can be published by a rerun.
partial_repo="$(new_fixture partial)"
partial_sha="$(git -C "$partial_repo" rev-parse HEAD)"
git -C "$partial_repo" tag -a apk/0.2.0 "$partial_sha" -m partial
assert_eq "$(run_script "$partial_repo" previous 0.2.0 "$partial_sha")" "null"
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

# Keep both workflow contracts testable without executing GitHub Actions.
build_workflow="${script_dir}/../workflows/manual-personal-apk.yml"
grep -qF "name: Build APK" "$build_workflow" || fail "build workflow was renamed ambiguously"
grep -qF "contents: read" "$build_workflow" || fail "build workflow is not read-only"
if grep -qF "contents: write" "$build_workflow"; then
    fail "build workflow can write repository contents"
fi
grep -qF 'release-apk-tag.sh previous' "$build_workflow" || fail "build workflow does not use safe previous-tag lookup"
if grep -qF 'release-apk-tag.sh publish' "$build_workflow"; then
    fail "build workflow publishes a release tag"
fi
grep -qF -- '-PreleaseHistoryMode=build' "$build_workflow" || fail "build workflow does not select build mode"
grep -qF -- '--arg mode "build"' "$build_workflow" || fail "build metadata lacks build mode"
grep -qF -- '--argjson versionCode' "$build_workflow" || fail "build metadata lacks numeric versionCode"
grep -qF -- '--argjson releaseTag null' "$build_workflow" || fail "build metadata does not use a null release tag"
grep -qF 'baseVersionName:' "$build_workflow" || fail "build metadata lacks baseVersionName"
grep -qF 'variantVersionName:' "$build_workflow" || fail "build metadata lacks variantVersionName"
grep -qF 'commitSha:' "$build_workflow" || fail "build metadata lacks commitSha"
grep -qF 'previousReleaseTag:' "$build_workflow" || fail "build metadata lacks previousReleaseTag"
grep -qF 'artifact_path=${artifact_dir}' "$build_workflow" || fail "build artifact does not include APK and JSON"
assert_workflow_order "$build_workflow" \
    "- name: Read previous release tag" \
    "- name: Build debug APK" \
    "- name: Prepare APK artifact" \
    "- name: Upload APK"

release_workflow="${script_dir}/../workflows/release-personal-apk.yml"
grep -qF "name: Release APK" "$release_workflow" || fail "release workflow is not explicitly named"
grep -qF "expected_version_name:" "$release_workflow" || fail "release workflow lacks version confirmation input"
grep -qF "required: true" "$release_workflow" || fail "release version confirmation is optional"
grep -qF '"${version_name}" != "${EXPECTED_VERSION_NAME}"' "$release_workflow" || fail "release workflow does not enforce version confirmation"
grep -qF "contents: write" "$release_workflow" || fail "release workflow lacks tag push permission"
grep -qF "fetch-depth: 0" "$release_workflow" || fail "release workflow uses shallow checkout"
grep -qF "cancel-in-progress: false" "$release_workflow" || fail "release workflow cancels an active release"
grep -qF -- '-PreleaseHistoryMode=release' "$release_workflow" || fail "release workflow does not select release mode"
grep -qF -- '--arg mode "release"' "$release_workflow" || fail "release metadata lacks release mode"
grep -qF -- '--argjson versionCode' "$release_workflow" || fail "release metadata lacks numeric versionCode"
grep -qF -- '--arg releaseTag "${RELEASE_TAG}"' "$release_workflow" || fail "release metadata lacks current release tag"
grep -qF 'baseVersionName:' "$release_workflow" || fail "release metadata lacks baseVersionName"
grep -qF 'variantVersionName:' "$release_workflow" || fail "release metadata lacks variantVersionName"
grep -qF 'commitSha:' "$release_workflow" || fail "release metadata lacks commitSha"
grep -qF 'previousReleaseTag:' "$release_workflow" || fail "release metadata lacks previousReleaseTag"
grep -qF 'GIT_COMMITTER_NAME: github-actions[bot]' "$release_workflow" || fail "release workflow lacks an annotated-tag identity"
assert_workflow_order "$release_workflow" \
    "- name: Confirm release source and version" \
    "- name: Preflight release tag" \
    "- name: Build release APK" \
    "- name: Verify and prepare release artifact" \
    "- name: Upload release APK" \
    "- name: Publish annotated APK release tag"

echo "All release APK tag tests passed."
