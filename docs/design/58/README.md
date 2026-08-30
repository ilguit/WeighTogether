# UI/UX audit — issue #58

## Status

- Issue: https://github.com/ilguit/XiaomiScaleSync/issues/58
- Approved: 2026-08-30
- Design specification revision 3: https://github.com/ilguit/XiaomiScaleSync/issues/58#issuecomment-5468847732
- Final design review: https://github.com/ilguit/XiaomiScaleSync/issues/58#issuecomment-5469027956
- Owner approval: https://github.com/ilguit/XiaomiScaleSync/issues/58#issuecomment-5469036396

The linked specification is the canonical source. This package preserves its
approved text and the final interactive prototype. Rejected settings and
full-screen resolver variants are intentionally omitted.

## Scope

- Measurements summary, pending queue, history, editor, and resolver.
- Charts, filters, and their data states.
- Unified Profiles and Pets selector.
- Profiles and Pets settings sub-screen and both full-screen editors.
- Pet screen, pet measurement entry, two-reading flow, and result.
- Unsaved preview, changelog, shared feedback, spacing, and accessibility rules.

## Non-scope

- Settings root and settings sub-screens other than Profiles and Pets.
- Measurement, recognition, deduplication, and pet-weight algorithms.
- Increased system font, dark theme, tablet-specific information architecture,
  second language, avatars, new animation, and Huawei removal.
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
widths with long text. Increased system font is explicitly outside issue #58.

Both flavors use the same UX for the same capability. Huawei Health is absent
from personal and visible only where the enterprise capability exists.

## Accessibility and safety

- Touch targets are at least 48 dp.
- Color is never the only signal.
- Icon-only actions have labels; selection, expansion, status, and graph summaries
  have screen-reader semantics.
- Focus enters new surfaces at their title and returns to the trigger on exit.
- Pet live weight is not announced continuously.
- Explicit user deletion has confirmation or Undo as specified. Auto-ignore and
  background tombstone behavior remain unchanged.

## Files

- `design-specification.md` — approved revision 3 copied from the issue.
- `prototype.html` — standalone interactive prototype for the profile, resolver,
  preview, and pet-measurement flows.
- `preview.png` — primary prototype preview.
- `preview-pet-result.png` — pet result with delta from the prior measurement.

## Prototype use

Open `prototype.html` in a browser. Use the Scenario and Width selectors to
inspect the represented screens.

The prototype demonstrates visual hierarchy, copy, selection controls,
full-width resolver actions, narrow width, arbitrary-order pet instructions,
and the pet-result delta. It does not implement persistence, real navigation,
calculations, Android integrations, production Compose semantics, or every
screen in the specification. Existing business logic remains authoritative.
