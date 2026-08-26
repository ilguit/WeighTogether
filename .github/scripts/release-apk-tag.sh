#!/usr/bin/env bash

set -euo pipefail

usage() {
    echo "Usage: $0 <previous|preflight|publish> <versionName> <40-character commit SHA>" >&2
    exit 2
}

die() {
    echo "release-apk-tag: $*" >&2
    exit 1
}

[[ $# -eq 3 ]] || usage
command_name="$1"
version_name="$2"
commit_sha="${3,,}"

[[ "$command_name" == "previous" || "$command_name" == "preflight" || "$command_name" == "publish" ]] || usage
[[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]] ||
    die "invalid base versionName '$version_name'"
[[ "$commit_sha" =~ ^[0-9a-f]{40}$ ]] || die "commit SHA must contain exactly 40 hexadecimal characters"
git cat-file -e "${commit_sha}^{commit}" 2>/dev/null || die "commit $commit_sha does not exist"
[[ "$(git rev-parse "${commit_sha}^{commit}")" == "$commit_sha" ]] || die "$commit_sha does not identify a commit directly"
git remote get-url origin >/dev/null 2>&1 || die "Git remote 'origin' is not configured"

tag_name="apk/${version_name}"

# Refresh remote tags before every decision. A stale, unpublished local tag is
# deliberately not treated as a release boundary.
git fetch --force --tags origin >/dev/null

remote_tag_target() {
    local requested_tag="$1"
    local direct=""
    local peeled=""
    local output
    local object ref

    if ! output="$(git ls-remote --tags origin "refs/tags/${requested_tag}" "refs/tags/${requested_tag}^{}")"; then
        echo "release-apk-tag: failed to query remote tag $requested_tag" >&2
        return 2
    fi
    while read -r object ref; do
        [[ -n "${object:-}" ]] || continue
        if [[ "$ref" == "refs/tags/${requested_tag}" ]]; then
            direct="$object"
        elif [[ "$ref" == "refs/tags/${requested_tag}^{}" ]]; then
            peeled="$object"
        fi
    done <<<"$output"

    [[ -n "$direct" ]] || return 1
    if [[ -z "$peeled" ]]; then
        echo "release-apk-tag: remote tag $requested_tag exists but is not an annotated tag" >&2
        return 2
    fi
    printf '%s\n' "$peeled"
}

check_current_remote_tag() {
    local target status
    if target="$(remote_tag_target "$tag_name")"; then
        if [[ "$target" != "$commit_sha" ]]; then
            die "version $version_name is already released from $target, not $commit_sha"
        fi
        return 0
    else
        status=$?
        [[ $status -eq 1 ]] || die "cannot safely determine the state of $tag_name"
    fi
    return 1
}

previous_release_tag() {
    local object ref name position output commit
    local -a reachable=()
    declare -A annotated=()
    declare -A first_parent_position=()

    output="$(git ls-remote --tags origin 'refs/tags/apk/*')" || die "failed to query remote APK tags"
    while read -r object ref; do
        [[ -n "${object:-}" ]] || continue
        if [[ "$ref" == refs/tags/apk/*^\{\} ]]; then
            name="${ref#refs/tags/}"
            name="${name%\^\{\}}"
            annotated["$name"]="$object"
        fi
    done <<<"$output"

    position=0
    while read -r commit; do
        [[ -n "${commit:-}" ]] || continue
        first_parent_position["$commit"]="$position"
        ((position += 1))
    done < <(git rev-list --first-parent "$commit_sha")

    for name in "${!annotated[@]}"; do
        [[ "$name" != "$tag_name" ]] || continue
        object="${annotated[$name]}"
        git cat-file -e "${object}^{commit}" 2>/dev/null || continue
        [[ -n "${first_parent_position[$object]+x}" ]] || continue
        reachable+=("${first_parent_position[$object]} ${name}")
    done

    if ((${#reachable[@]} == 0)); then
        printf 'null\n'
    else
        printf '%s\n' "${reachable[@]}" | LC_ALL=C sort -k1,1n -k2,2 | sed -n '1p' | cut -d' ' -f2-
    fi
}

if [[ "$command_name" == "previous" ]]; then
    previous_release_tag
    exit 0
fi

if [[ "$command_name" == "preflight" ]]; then
    if check_current_remote_tag; then
        echo "release-apk-tag: $tag_name already points to $commit_sha; preflight is idempotent" >&2
    fi
    previous_release_tag
    exit 0
fi

if check_current_remote_tag; then
    echo "release-apk-tag: $tag_name already points to $commit_sha; nothing to publish" >&2
    exit 0
fi

previous_tag="$(previous_release_tag)"
if git show-ref --verify --quiet "refs/tags/${tag_name}"; then
    local_target="$(git rev-parse "${tag_name}^{commit}")"
    [[ "$local_target" == "$commit_sha" ]] ||
        die "local tag $tag_name points to $local_target, not $commit_sha"
    [[ "$(git cat-file -t "$tag_name")" == "tag" ]] || die "local tag $tag_name is not annotated"
else
    git tag --annotate "$tag_name" "$commit_sha" \
        --message="APK release ${version_name}

Version: ${version_name}
Commit: ${commit_sha}
Previous release tag: ${previous_tag}"
fi

# Close the normal pre-push race window. The failed-push branch below handles a
# tag that appears after this check.
if check_current_remote_tag; then
    echo "release-apk-tag: $tag_name appeared remotely at $commit_sha; nothing to publish" >&2
    exit 0
fi

if git push origin "refs/tags/${tag_name}:refs/tags/${tag_name}"; then
    echo "release-apk-tag: published annotated tag $tag_name at $commit_sha" >&2
    exit 0
fi

echo "release-apk-tag: push failed; checking whether a concurrent publisher won the race" >&2
git fetch --force --tags origin >/dev/null
if check_current_remote_tag; then
    echo "release-apk-tag: $tag_name was concurrently published at $commit_sha; treating as success" >&2
    exit 0
fi
die "failed to publish $tag_name"
