# Issue #58 QA visual fidelity cycle

## Provenance

- Captured 2026-09-10 from production Compose at commit `404a876` on a physical
  Google Pixel 7 Pro (`cheetah`), Android API 36.
- `412dp`: physical 1440×3120 at density 560.
- `320dp`: temporary 1120×2427 override at density 560.
- `font-200`: physical width with temporary system `font_scale=2.0`.
- Display size and font scale were restored after capture.
- These frames supersede earlier implementation screenshots only for the QA surfaces
  listed below. Earlier implementation screenshots were not used as the fidelity baseline.

## Targeted evidence

| Surface | 412 dp | 320 dp | Comparison |
| --- | --- | --- | --- |
| Profiles and pets | `implementation-screenshots/412dp/profiles.png` | `implementation-screenshots/320dp/profiles.png` | `comparisons/{width}-profiles.png` |
| Recognition section | `implementation-screenshots/412dp/profiles-recognition.png` | `implementation-screenshots/320dp/profiles-recognition.png` | `comparisons/{width}-profiles-recognition.png` |
| Human editor | `implementation-screenshots/412dp/human-editor.png` | `implementation-screenshots/320dp/human-editor.png` | `comparisons/{width}-human-editor.png` |
| Pet editor | `implementation-screenshots/412dp/pet-editor.png` | `implementation-screenshots/320dp/pet-editor.png` | `comparisons/{width}-pet-editor.png` |
| Resolver | `implementation-screenshots/412dp/resolver.png` | `implementation-screenshots/320dp/resolver.png` | `comparisons/{width}-resolver.png` |
| Human editor, 200% font | `implementation-screenshots/font-200/human-editor-font200.png` | — | `comparisons/font-200-human-editor-font200.png` |
| Pet editor, 200% font | `implementation-screenshots/font-200/pet-editor-font200.png` | — | `comparisons/font-200-pet-editor-font200.png` |

`{width}` is `412dp` or `320dp`.

## Direct prototype comparison verdict

- Profile and pet glyphs, the primary badge, pet measurement subtitle, and row hierarchy
  match `prototype-frames/normal-profiles.png` and `narrow-profiles.png`.
- Recognition is fully visible in its dedicated frame. It has no visible Save button;
  the explanatory copy, weight tolerance field, and unknown-reading option are present.
- Human sex and pet species/sex controls use the prototype's solid selected and outlined
  unselected button treatment at both widths and remain operable at 200% font.
- The resolver fixture deliberately supplies a non-candidate first and the candidate second.
  The second row still visibly carries `Рекомендуется`, proving that recommendation display
  is based on candidate identity rather than row position.
- No affected-frame mismatch requiring another production change was found. Differences are
  limited to native Android system bars, Material typography metrics, and the physical device's
  taller viewport.
