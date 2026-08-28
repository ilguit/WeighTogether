# Settings redesign — issue #54

## Status

- Issue: https://github.com/ilguit/XiaomiScaleSync/issues/54
- Approved: 2026-08-28
- Design specification:
  - https://github.com/ilguit/XiaomiScaleSync/issues/54#issuecomment-5453277981
  - https://github.com/ilguit/XiaomiScaleSync/issues/54#issuecomment-5453360623
  - https://github.com/ilguit/XiaomiScaleSync/issues/54#issuecomment-5453408257
  - https://github.com/ilguit/XiaomiScaleSync/issues/54#issuecomment-5453429813
- Final design review: https://github.com/ilguit/XiaomiScaleSync/issues/54#issuecomment-5453445992
- Approval: https://github.com/ilguit/XiaomiScaleSync/issues/54#issuecomment-5453462152

The linked comments form one specification. Later comments replace only the
state/action rows explicitly named in them.

## Scope

- Replace settings accordions with a compact root screen and nested detail screens.
- Keep this order: Profiles; Scales and synchronization; Backup; Diagnostics; Version history.
- Replace user-visible “Account” terminology with “Profile” in all affected flows.
- Keep existing forms, confirmations, state machines, callbacks, data, and business rules.
- Keep dangerous actions out of the root screen and show them only when applicable.

## Non-scope

- Renaming internal Kotlin `Account` types or repository APIs.
- Changing backup formats or synchronization, recognition, import, or deletion rules.
- Adding new integration callbacks or system intents.
- Redesigning screens outside the affected settings flows.

## Navigation

- Profiles, scales, integrations, backup, and diagnostics open nested screens.
- Nested screens show top-bar Back and hide bottom navigation.
- System Back returns to settings.
- Focus moves to Back/title on entry and returns to the originating row on exit.

## State and action contract

| Surface | State | Status / presentation | Primary action | Dangerous action |
|---|---|---|---|---|
| Scales | ready | selected model, ready | existing selected-scale actions | forget selected scales |
| Scales | empty | scales not selected | select scales | hidden |
| Scales | checking | search in progress | disabled progress/action | hidden |
| Scales | unavailable | actual Bluetooth/permission reason | existing system-settings action | hidden |
| Scales | error | search failed | retry search | hidden |
| Health Connect | connected | connected | open Health Connect | disconnect |
| Health Connect | permission required | permission required | connect Health Connect | hidden |
| Health Connect | checking | checking | disabled progress/action | hidden |
| Health Connect | locally disabled | disabled in app | connect again | hidden |
| Health Connect | unavailable/provider update required | exact existing reason | none | hidden |
| Health Connect | check failed | check failed | connect through existing authorization flow | hidden |
| Huawei Health | authorized | connected | none | disconnect |
| Huawei Health | authorization required | not connected | authorize access | hidden |
| Huawei Health | checking | checking | disabled progress/action | hidden |
| Huawei Health | locally disabled | disabled in app | connect again | hidden |
| Huawei Health | unavailable | exact existing reason | none | hidden |
| Huawei Health | check failed | check failed | existing retry action | hidden |
| Backup | idle | existing export/import/replace rows | selected operation | replace uses confirmation flow |
| Backup | busy | existing inline progress | duplicate start unavailable | unchanged |
| Backup | error | existing inline/dialog error | existing retry/close | unchanged |
| Backup | confirmation | existing confirmation dialog | confirm/cancel | only after confirmation |

The prototype groups related states into `ready`, `empty`, `checking`,
`disabled`, `unavailable`, and `error` demonstration modes. Implementation must
use the more precise existing presentation states described above.

## Flavor and visual contract

- `personal`: Huawei row and screen are absent.
- `huaweiEnterprise`: Huawei is visible with its actual state.
- HuaweiTheme palette: `#28766B` primary, `#F5F7F3` background, white surfaces.
- Empty and unavailable rows remain visible with textual statuses, except Huawei in personal.
- Rows use an icon, title, short textual status, and navigation indicator.
- Layouts must work at regular and narrow widths, with long names and increased font scale.

## Accessibility

- Status is expressed in text and never by color/icon alone.
- Controls expose clear labels and screen-reader semantics.
- Focus/pressed indication remains visible.
- Touch targets follow platform requirements.
- Large text wraps without clipping and rows grow vertically.
- Inapplicable dangerous actions are absent from the semantics tree.

## Files

- `prototype.html` — standalone interactive prototype; open locally in a browser.
- `preview.png` — regular personal ready state.
- `preview-personal-unavailable.png` — personal unavailable state.
- `preview-enterprise-ready.png` — enterprise ready state.
- `preview-enterprise-unavailable.png` — enterprise unavailable state.
- `preview-large-narrow.png` — narrow layout, large text, and long names.
- `preview-health-connected.png` — connected Health Connect detail actions.
- `preview-huawei-disabled.png` — locally disabled Huawei detail action.

## Prototype limitations

The prototype demonstrates hierarchy, navigation, representative states,
terminology, and visual behavior. It does not implement Android integrations,
persistence, dialogs, real system navigation, or production Compose semantics.
Those behaviors remain governed by existing application logic and must be
verified during Coding with instrumentation and manual visual checks.
