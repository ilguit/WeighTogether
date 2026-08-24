# Release history baseline

`baseline.yaml` is the closed, one-time bridge for releases that predate
release-note fragments. Its top-level `boundaryCommit` is the exact generator
cutoff: the last first-parent commit before the fragment contract was merged.
New releases must be derived from tags and fragments; the baseline must not be
extended and is never a fallback for missing fragments.

Historical `evidence` is documentation, not generator input. A `confirmed`
entry requires an immutable release boundary in `boundaryCommit`; an `unknown`
entry instead records the best `candidateCommit`. Candidates must never be
treated as `apk/<version>` tags or range boundaries.

The migrated versions have no historical release tags or immutable APK
metadata, so their candidates are the last known first-parent commits carrying
the corresponding `versionName`:

| Version | Version-bump commit | Candidate last commit | Evidence |
| --- | --- | --- | --- |
| 0.1.6 | `bd5bd96916e905096c69ca81b78eb0a80027d1c7` | `660709d548deef69504d93d873f987cad512199d` | unknown; Git version span and #19/#20 issue handoffs |
| 0.1.5 | `3ff290d35c075a87a7e405be0833c8e013569f01` | `f36d7f132beb518fdc689690dca14d4b02b8ba58` | unknown; Git version span and the previous manual catalog |
| 0.1.4 | `2be96b8b94f780f181e6d6085bfcac22f1c36cee` | same | unknown; Git version span and the previous manual catalog |
| 0.1.3 | `2b9391effc4d26f4ca822008316bd8b1891d9d67` | `d1eb07d826e0ca4cff186039514c84ab03c6843e` | unknown; Git version span and the previous manual catalog |
| 0.1.2 | `b665e736ed6d86dff60ccb1063584624061274e4` | same | unknown; Git version span and the previous manual catalog |
| 0.1.1 | `0d4419874db233d040992ef7c85a481a54f4619f` | `6c88c9935ee27c65b83008b001e4128dd0bf7ba5` | unknown; Git version span and the previous manual catalog |

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
