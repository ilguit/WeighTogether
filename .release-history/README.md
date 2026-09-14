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
metadata. The baseline therefore contains only versions explicitly handed off
as APK releases in the issue history; a `versionName` span or an implementation
commit alone is not release evidence. Candidate commits remain documentation
and do not promote omitted work into the user-visible history:

| Version | Version-bump commit | Candidate last commit | Evidence |
| --- | --- | --- | --- |
| 0.1.6 | `bd5bd96916e905096c69ca81b78eb0a80027d1c7` | `660709d548deef69504d93d873f987cad512199d` | unknown; explicit APK handoff covers #20, while #19 only maintained the catalog and is omitted |
| 0.1.4 | `2be96b8b94f780f181e6d6085bfcac22f1c36cee` | same | unknown; explicit APK handoff and the previous manual catalog |
| 0.1.3 | `2b9391effc4d26f4ca822008316bd8b1891d9d67` | `d1eb07d826e0ca4cff186039514c84ab03c6843e` | unknown; explicit APK handoff and the previous manual catalog |
| 0.1.2 | `b665e736ed6d86dff60ccb1063584624061274e4` | same | unknown; explicit APK handoff covers #4; later workflow work from #12 is omitted |
| 0.1.1 | `0d4419874db233d040992ef7c85a481a54f4619f` | `6c88c9935ee27c65b83008b001e4128dd0bf7ba5` | unknown; explicit APK handoff covers #6, #8, and #11; later flavor work from #7 is omitted |

Version 0.1.5 is intentionally absent: the repository records a version bump
and catalog maintenance, but no explicit APK release handoff. Empty historical
versions are not emitted.

Generation is pinned to the exact checked-out commit and its full ancestry,
including every merge parent. Reachable annotated `apk/<version>` tags are
release boundaries and must form one ancestor chain with strictly increasing
numeric dotted versions (two- and three-component versions are supported;
missing components compare as zero). Multiple tags at one commit, incomparable
tagged commits, and non-increasing versions fail explicitly. Unreachable and
lightweight tags are ignored. The shell uses the same chain rules but considers
only published remote tags. The baseline boundary is also checked using full
ancestry. CI must therefore check out full history and tags. A repeated build
of an already tagged commit is deterministic and does not add a duplicate
release.

The Gradle task is cacheable: the exact HEAD, full ancestry metadata, APK tag
refs, baseline, fragments, flavor, version, and generator schema are declared
inputs; generated Kotlin and canonical `res/raw/app_release_history.json` are
declared outputs. Tracked worktree state is also an input so a dirty-tree guard
cannot be bypassed by an up-to-date or build-cache hit. Untracked files do not
affect generation.
