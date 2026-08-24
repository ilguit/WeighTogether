#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "${script_dir}/../.." && pwd -P)"
validator_project="${repository_root}/tools/release-notes-validator"

[[ -x "${repository_root}/gradlew" ]] || {
    echo "release-note validator: executable Gradle wrapper not found at ${repository_root}/gradlew" >&2
    exit 1
}
[[ -f "${validator_project}/settings.gradle.kts" ]] || {
    echo "release-note validator: standalone validator project not found at ${validator_project}" >&2
    exit 1
}

exec "${repository_root}/gradlew" \
    --project-dir "${validator_project}" \
    run \
    "--args=\"${repository_root}\""
