# Issue #58 implementation screenshot evidence

> QA cycle 2026-09-10: the focused post-QA captures and direct prototype comparisons for
> profiles, recognition, both editors, and resolver are in
> `qa-cycle-2026-09-10/README.md`. Those frames supersede the corresponding earlier
> implementation evidence; earlier implementation screenshots are not the QA baseline.

## Capture provenance

- Captured 2026-09-10 from the production Compose functions in the issue #58 branch.
- Physical device: Google Pixel 7 Pro (`cheetah`), Android API 36.
- `412dp`: native 1440×3120 pixels at density 560 (411.43 dp effective width).
- `320dp`: temporary 1120×2427 pixel display-size override at density 560.
- `font-200`: native width with temporary system `font_scale=2.0`.
- Original device settings were restored after capture: physical 1440×3120, physical
  density 560, `font_scale=1.0`.
- Frames were captured by `Issue58ScreenshotMatrixTest` using Android `screencap`
  while the real production composables were hosted by `ComponentActivity`. They are
  not HTML substitutions, previews, or semantics dumps.

## Evidence layout

- `implementation-screenshots/412dp/`: 24 production Compose frames.
- `implementation-screenshots/320dp/`: the same 24-frame matrix at narrow width.
- `implementation-screenshots/font-200/`: human sex and pet species/sex controls at 200%.
- `prototype-frames/`: the eight approved HTML prototype surfaces at both widths.
- `comparisons/`: all 50 implementation frames placed beside the closest corresponding
  approved prototype frame. State variants use the baseline frame for that surface because
  the prototype defines only its normal state.

## Whitelist matrix

| Surface | 412 dp | 320 dp | Prototype comparison |
| --- | --- | --- | --- |
| Profiles and pets | `412dp/profiles.png` | `320dp/profiles.png` | `comparisons/{width}-profiles.png` |
| Human profile editor | `412dp/human-editor.png` | `320dp/human-editor.png` | `comparisons/{width}-human-editor.png` |
| Pet editor | `412dp/pet-editor.png` | `320dp/pet-editor.png` | `comparisons/{width}-pet-editor.png` |
| Resolver | `412dp/resolver.png` | `320dp/resolver.png` | `comparisons/{width}-resolver.png` |
| Unsaved preview | `412dp/unsaved-preview.png` | `320dp/unsaved-preview.png` | `comparisons/{width}-unsaved-preview.png` |
| Pet first measurement | `412dp/pet-first.png` | `320dp/pet-first.png` | `comparisons/{width}-pet-first.png` |
| Pet second measurement | `412dp/pet-second.png` | `320dp/pet-second.png` | `comparisons/{width}-pet-second.png` |
| Pet result | `412dp/pet-result.png` | `320dp/pet-result.png` | `comparisons/{width}-pet-result.png` |

`{width}` is `412dp` or `320dp`. All paths above are relative to
`implementation-screenshots/` unless they begin with `comparisons/`.

## State and edge-case matrix

Both `412dp/` and `320dp/` additionally contain:

- `profiles-empty.png`;
- `human-editor-long.png`, `human-editor-saving.png`, `human-editor-error.png`;
- `pet-editor-long.png`, `pet-editor-saving.png`, `pet-editor-error.png`;
- `resolver-saving.png`;
- `unsaved-step-1.png`, `unsaved-step-2.png`, `unsaved-step-3.png`,
  `unsaved-calculating.png`, `unsaved-error.png`;
- `pet-result-no-history.png`, `pet-saving.png`, `pet-unavailable.png`.

The pet-result frames visibly contain both **Отмена** and **Готово**. `pet-result.png`
contains the previous-measurement delta; `pet-result-no-history.png` intentionally omits it.

Loading/empty/unavailable states that cannot occur for a surface by its production state
contract are not fabricated. The captured applicable equivalents are operation saving,
preview calculation, terminal errors, profile empty, and interrupted/unavailable scale.

## Visual comparison result

The 50 side-by-side pairs preserve the approved hierarchy, labels, full-width actions,
selected controls, nested navigation, and the three-step/pet-weighing structures. Production
Compose naturally uses native Material typography, system bars, and the full physical aspect
ratio rather than the prototype's fixed browser phone frame.

At 320 dp the production surfaces remain readable and scrollable; actions do not overlap.
At 200% font, human sex choices reflow vertically and pet species/sex choices remain distinct,
readable, and operable without text overlap. Long names do not cover adjacent controls.

The production unsaved-preview first frame includes the raw measurement summary before its
temporary profile inputs, while the compact prototype starts directly with the sex choice.
This is an intentional fidelity difference: the approved specification preserves the raw
summary as step 1 and the implementation tests retain its three-step state/navigation contract.

No visual mismatch requiring a production code change was found.

## Verification

- Targeted screenshot instrumentation passed for `normal`, `narrow`, and `font200` modes.
- Existing connected Compose-rule coverage was attempted for 36 focused tests. On this API 36
  device all Compose-rule tests stop before their bodies because `kotlinx-coroutines-test` 1.11.0
  cannot load `ExceptionCollectorAsService` through Android `ServiceLoader`; pet-editor fixtures
  additionally fail during construction because the device catalog does not contain the test's
  hard-coded «Метис» lookup. These are pre-test infrastructure/fixture failures, not visual or
  callback assertion failures. The rule-free screenshot instrumentation passed in all three modes.
- JVM unit tests and AndroidTest compilation passed.
- Release-note fragment validation and `git diff --check` passed.
