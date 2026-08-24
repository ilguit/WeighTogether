# Release history baseline

`baseline.yaml` is the one-time, versioned bridge for releases that predate
release-note fragments. Its boundary is the last first-parent commit before the
fragment contract was merged. New releases must be derived from fragments; this
file is not a fallback for missing fragments and must not be extended.

Generation is pinned to the exact checked-out commit and its first-parent
history. Only annotated `apk/<version>` tags on that chain are release
boundaries. CI must therefore check out full history and tags. A repeated build
of an already tagged commit is deterministic and does not add a duplicate
release.

The Gradle task is cacheable: the exact HEAD, first-parent metadata, APK tag
refs, baseline, fragments, flavor, version, and generator schema are declared
inputs; generated Kotlin and canonical `res/raw/app_release_history.json` are
declared outputs. Tracked worktree state is also an input so a dirty-tree guard
cannot be bypassed by an up-to-date or build-cache hit. Untracked files do not
affect generation.
