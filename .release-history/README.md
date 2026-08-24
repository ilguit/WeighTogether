# Release history baseline

`baseline.yaml` is the one-time, versioned bridge for releases that predate
release-note fragments. Its boundary is the last first-parent commit before the
fragment contract was merged. New releases must be derived from fragments; this
file is not a fallback for missing fragments and must not be extended.
