# Native redesign foundation

Approved direction: Designer round 3. This layer owns theme, shared primitives, root drawer, and routes — not rebuilt screen content. Do not merge before Critic passes the built result.

## Shared UI API

- `DevinLightColors` / `DevinDarkColors`: every supported Material 3 role supplied explicitly. `DevinFixedColors` supplies the 12 fixed roles unsupported by Material 3 1.3; they are invariant in light/dark.
- `DevinTypography`: all 15 UI styles use platform Roboto/SansSerif. Only `DevinTextStyles.welcomeSerif` / `documentHero` use Serif. `codeBlock` uses Monospace. All sizing is sp.
- `DevinDimens`: `touchTarget` (48dp), `screenPadding` (20dp), `sectionGap` (24dp), `itemGap` (12dp), `outlineWidth` (opaque 1dp boundary).
- `DevinIconButton`, `DevinTopBar`, `DevinOutlinedSurface`, `DevinSearchField`, `KnownCountBadge`: accessible native primitives. Counts that are unknown or zero produce no Badge node.
- `DevinRootShell`: used by Home and Sessions, with actual visible destination selected. Needs you is a Sessions filter shortcut. Close drawer before navigation; system Back dismisses it first.
- Keep the exact `UnofficialDisclaimer`. No product artwork or Bots destination.

System theme, explicit Light/Dark, and the existing opt-in Android dynamic color setting remain supported; static is the default approved palette. Native system bars/insets stay real, not drawn phone chrome.

## Route ownership

Use `ui/navigation/Routes.kt` builders instead of interpolating raw arguments. Session IDs, file identities, versions, and shared prompts are encoded independently.

| Entry | Destination / Back | Retained state |
| --- | --- | --- |
| Launcher | Home / system exits | Home prompt and recents |
| Home recent | Chat / Home | Home scroll and prompt |
| Sessions row, including Needs you | Chat / prior Sessions | Query, owner, filter, list anchor |
| Cold session URL or notification | Home → Chat / Home then exit | Single synthetic Home parent |
| Warm session URL or notification | Push Chat / exact prior route | Do not pop Reader, Chat, Settings or Create; same top session reuses entry |
| Chat ↔ Files | Same Session entry / Files Back selects Chat first | Independently saved list/chat anchors and composer draft |
| File card or row | Reader / opening Chat or Files tab | File identity, version and reading anchor |
| Resolvable direct-file link | Home → Chat → Reader | No invented document API route; unresolved metadata stays a session file error |
| New session | Create / root origin | Persist draft edits; Cancel does not erase |
| Create success | Replace only Create with Chat / root origin | Clear draft only after confirmed API success |
| Cold share | Home → Create / Home | Merge/choose existing draft explicitly; never overwrite |
| Drawer Settings | Settings / prior root | Root state survives; account change clears protected caches |
| External browser/viewer | Android activity / exact internal origin | Never push an internal web destination |

`Routes.Reader` is a **reserved pattern**, not yet registered: the Reader screen change must register it and resolve an attachment by identity from the session index. Do not send arbitrary remote URLs through navigation arguments. `SessionTab` and `SessionNavigationState` define shared state keys for Chat/Files ↔ Reader. The Chat/Files owner implements tab-first Back; the Reader owner implements same-version anchor restoration and drops the anchor when content version changes. These detailed screen flows remain integration work, not claimed as completed by this foundation.

## Back and restoration

Root changes use Navigation save/restore state so Home/Sessions do not accumulate. Details, Settings and Create push onto the current origin; successful Create removes only its own entry. Framework NavHost owns system/predictive Back for routes. Shell Back only intercepts an open drawer. Keep Up and system Back equivalent inside this single activity. Screen owners must save scroll, filters and drafts in saveable state / persisted storage, not route URLs.

Each Create entry retains its last-persisted draft in saveable state. Save fresh incoming share arguments and actual edits, but do not autosave an unchanged form merely because it resumed: a newer Create entry may have saved a newer draft. Keep this guard when rebuilding the form.

## Validation

Run `./gradlew testDebugUnitTest assembleDebug lintDebug`. Route tests cover encoding and the filter contract; contrast tests protect readable color pairs and control edges in both schemes. On-device QA covers root selection, drawer dismissal, warm/cold links and Create origin. Full rebuilt-screen TalkBack, Reader, Files and motion acceptance belongs to their screen changes.
