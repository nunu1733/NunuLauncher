# Issue #56: Deck runtime retirement assessment

> Status: completed assessment/research
> Decision status: accepted in [ADR-0006](../adr/0006-retire-deck-runtime.md)
> Completed: 2026-08-15
> Local baseline: `6bfad79bd96b5b6271a5cf857ea46c03b6d556ef`
> Upstream baseline: Lawnchair `505dbc40e6154c05158b5d0271c45f6a885a411b`

## Question and method

This completed research record inventories the remaining Deck runtime at the local
baseline. It is evidence only. Durable choices are accepted in
[ADR-0006](../adr/0006-retire-deck-runtime.md). Observable behavior is described
by [Issue #57](https://github.com/nunu1733/NunuLauncher/issues/57) and its
[accepted spec](../../specs/57-deck-runtime-retirement/spec.md). Implementation
planning is owned by the [Issue #57 plan](../../specs/57-deck-runtime-retirement/plan.md).

The review covered Deck code, preference keys and readers, package handling,
drag/delete behavior, write-coordinator names, raw database artifacts, backups,
resources, and the compatibility fixture. It also reviewed the corrective findings
in [Issue #44's shared-writer audit](issue-44-shared-writer-audit.md).

## Observed runtime behavior

[`LawndeckManager.enableLawndeck`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt#L35-L53)
creates a `bk` copy when absent, restores a `lawndeck` copy when present, or
classifies and places apps otherwise. Its
[`disableLawndeck`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt#L55-L60)
path creates `lawndeck` then restores `bk`.
[`AddFoldersWithItemsTask`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt#L25-L145)
is the folder-write task used by that runtime.

[`getDatabaseFiles`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt#L62-L91)
uses the exact artifact names `bk_<basename>`, `lawndeck_<basename>`,
`bk_<basename>-journal`, and `lawndeck_<basename>-journal`. The installed
Launcher's finite grid-database inventory is declared in
[`LauncherFiles`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/LauncherFiles.java#L34-L40).

The enable path changes `swipeUpGesture` to `NoOp` and `addIconToHome` to `true`;
the disable path changes the swipe gesture to `OpenAppDrawer`.
[`HomeLayoutPreferences`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt#L112-L143)
contains those writes and does not show retention of pre-Deck values.

[`PackageUpdatedTask`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/model/PackageUpdatedTask.java#L456-L473)
has a Deck-specific `OP_ADD` tail that reads the preference and invokes the
manager; its Deck import is at
[lines 71-74](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/model/PackageUpdatedTask.java#L71-L74).

## Runtime and UI inventory

| Area | Observed evidence |
|---|---|
| Deck runtime | [`LawndeckManager`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt#L29-L249) contains enable, disable, raw copy, raw restore, and package-placement entry points. [`AddFoldersWithItemsTask`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt#L25-L145) supplies folder writes. |
| Persisted keys | [`PreferenceManager2`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt#L682-L691) defines `enable_lawn_deck` through `deckLayout` and `show_deck_layout` through `showDeckLayout`. |
| Deck control | [`HomeLayoutPreferences`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt#L35-L143) imports and constructs the manager and presents the enable/disable control. |
| UI readers and gates | [`HomeScreenPreferences`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt#L58-L92) reads `showDeckLayout`; [`ExperimentalFeaturesPreferences`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt#L65-L78) exposes it; [`PreferencesDashboard`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/ui/preferences/destinations/PreferencesDashboard.kt#L83-L154) gates App Drawer on `deckLayout`; [`GestureHandlerPreference`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/ui/preferences/components/GestureHandlerPreference.kt#L52-L80) gates gesture choices on `deckLayout`. |
| Drag and delete readers | [`DragController.java:553-556`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/dragndrop/DragController.java#L553-L556) reads `getDeckLayout()` to alter delete-target handling. [`DeleteDropTarget.java:118-120`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/DeleteDropTarget.java#L118-L120) changes removability, and [`lines 159-161`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/DeleteDropTarget.java#L159-L161) suppress accessibility deletion when the key is true. |
| Package reader | [`PackageUpdatedTask`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/model/PackageUpdatedTask.java#L456-L473) imports `LawndeckManager` and reads `getDeckLayout()` in the Deck `OP_ADD` tail. |
| Coordinator names | [`LayoutWriteCoordinator.java:52-59`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/model/LayoutWriteCoordinator.java#L52-L59) includes `DECK_FILE_RESTORE` in `OwnerKind`. [`Ports.kt:79`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt#L79) includes the matching `DECK_FILE_RESTORE` in `WriterKind`. |
| Lawnchair backup | [`LawnchairBackup.getFiles`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt#L140-L146) names `launcher.db`, shared preferences XML, the preferences database, and the DataStore protobuf; [`backup creation`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt#L182-L191) writes that map's existing files. |
| Resources | A local exact-key scan found 290 occurrences in 58 localized `lawnchair/res/values*/strings.xml` files. The fixed [`res` tree](https://github.com/nunu1733/NunuLauncher/tree/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/res) and [base strings lines 218-219](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/res/values/strings.xml#L218-L219) and [602-604](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/res/values/strings.xml#L602-L604) contain `show_deck_layout`, `show_deck_layout_description`, `home_lawn_deck_label`, `home_lawn_deck_label_beta`, and `home_lawn_deck_description`. |
| Compatibility corpus | [`ExampleCorpus`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/tests/unit/app/lawnchair/organizer/planning/harness/ExampleCorpus.kt#L819-L883) registers `deck-output-compatibility`; [`SyntheticFixtureGeneratorTest`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/tests/unit/app/lawnchair/organizer/planning/harness/SyntheticFixtureGeneratorTest.kt#L307-L318) asserts it. The fixed [`Quality Strategy`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/docs/engineering/quality-strategy.md#L63-L79) identifies existing Deck layout output as compatibility corpus evidence. |

## Shared-writer audit findings relevant to Deck

The [fixed Issue #44 audit](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/docs/assessment/issue-44-shared-writer-audit.md#L64-L109)
records that
[`LawndeckManager.restoreBackup`](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt#L72-L81)
holds `DECK_FILE_RESTORE` for raw DB and journal copying, then completes restore
actions after that scope. The audit reports source evidence of no single quiesced
operation across raw replacement, helper lifecycle, restore, and rebind; it does
not report a reproduced stale-handle data-loss trace.

The same audit records
[destructive source lock normalization](https://github.com/nunu1733/NunuLauncher/blob/6bfad79bd96b5b6271a5cf857ea46c03b6d556ef/src/com/android/launcher3/model/GridSizeMigrationUtil.java#L180-L199)
before grid migration success and additional writer-inventory, executor, reload,
FIFO, transaction, and restart gaps. Those findings are recorded as open audit
evidence, not resolved by this assessment.

## Conclusions

The observed Deck runtime spans a manager, a folder task, two persisted keys,
five preference-facing readers or gates, package placement, drag/delete behavior,
coordinator enum names, raw database artifacts, and localized labels. Lawnchair
backup carries the active database and preference stores separately from Deck raw
artifacts. The synthetic fixture is planner ingestion evidence, not a live runtime
dependency.

High-cost retirement choices are accepted in
[ADR-0006](../adr/0006-retire-deck-runtime.md). The intended externally visible
behavior is in [Issue #57](https://github.com/nunu1733/NunuLauncher/issues/57) and
its [accepted spec](../../specs/57-deck-runtime-retirement/spec.md). Implementation
source/path remove/migrate/retain inventory is canonically owned by the new
[Issue #57 plan](../../specs/57-deck-runtime-retirement/plan.md).
