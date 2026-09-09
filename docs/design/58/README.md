# UI/UX audit — issue #58

## Status

- Issue: https://github.com/ilguit/XiaomiScaleSync/issues/58
- Approved revision 17: 2026-09-09
- Design specification revision 17: https://github.com/ilguit/XiaomiScaleSync/issues/58#issuecomment-5606562418
- Final design review: https://github.com/ilguit/XiaomiScaleSync/issues/58#issuecomment-5606626133
- Owner approval: https://github.com/ilguit/XiaomiScaleSync/issues/58#issuecomment-5606733875

The linked specification is the canonical source. This package preserves its
approved text and the final interactive prototype. Rejected settings and
full-screen resolver variants are intentionally omitted.

## Scope

- Measurements summary, pending queue, history, editor, and resolver.
- Charts, filters, and their data states.
- Unified Profiles and Pets selector.
- Profiles and Pets settings sub-screen and both full-screen editors.
- Pet screen and profile editor; the existing pet-weighing flow is unchanged.
- Unsaved preview, changelog, shared feedback, spacing, and accessibility rules.

## Non-scope

- Settings root and settings sub-screens other than Profiles and Pets.
- Measurement, recognition, deduplication, and pet-weight algorithms.
- Any visual or behavioral change to pet weighing and to the existing breed picker.
- Existing zones, norms, scales, ranges, colors, labels, and interpretations for
  weight and all body-composition metrics.
- Full-app increased-font adaptation, dark theme, tablet-specific information
  architecture, second language, avatars, new animation, and Huawei removal.
  Species and sex button groups are still checked at 200% system font.
- Full string-resource migration, tracked separately by issue #63.
- Legacy/dead UI refactoring, tracked separately by issue #56.

## Navigation and structure

- Bottom navigation appears only on the three root destinations.
- Nested screens hide bottom navigation and provide Back plus a title.
- The unified horizontal selector lists human profiles before pets.
- Selecting a human switches root data; selecting a pet opens its nested screen.
- Resolver remains a dialog; profile and pet editors are full-screen.
- Returning restores the source, selection, scroll, filters, and expanded items.

## State and width contract

The full normal/empty/loading/unavailable/error matrix is preserved in
`design-specification.md`. Implementation is checked at standard and 320 dp
widths with long text. Full-app increased-font adaptation is outside issue #58;
the species and sex button groups are explicitly checked at 200%.

Both flavors use the same UX for the same capability. Huawei Health is absent
from personal and visible only where the enterprise capability exists.

## Accessibility and safety

- Touch targets are at least 48 dp.
- Color is never the only signal.
- Icon-only actions have labels; selection, expansion, status, and graph summaries
  have screen-reader semantics.
- Focus enters new surfaces at their title and returns to the trigger on exit.
- Explicit user deletion has confirmation or Undo as specified. Auto-ignore and
  background tombstone behavior remain unchanged.

## Files

- `design-specification.md` — approved specification including revision 17.
- `prototype.html` — standalone interactive prototype for profile management,
  editors, resolver, and unsaved preview flows.
- `preview.png` — primary prototype preview.

## Prototype use

Open `prototype.html` in a browser. Use the Scenario and Width selectors to
inspect the represented screens.

The prototype demonstrates visual hierarchy, copy, selection controls,
the current breed-picker entry, full-width resolver actions, and narrow width.
It intentionally omits pet weighing because that flow remains unchanged. It does not implement persistence, real navigation,
calculations, Android integrations, production Compose semantics, or every
screen in the specification. Existing business logic remains authoritative.
