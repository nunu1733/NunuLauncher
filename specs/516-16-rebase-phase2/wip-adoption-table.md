# WIP path/hunk採否表（初版）: 停止head WIP 253 path

> Status: draft（S0初版。plan §4.1 S0終了条件の「WIP path/hunk採否表」に対応）
> 対象: `git diff --name-only 8b35f1ff7ae35bca8e9e09f9650435f68d5c44a6 88af5218cea51b8556b9935b35d3640714b96111`（追加修復10 commitが触れた253 path）
> 分類・仮採否はpath単位の初期ラベルであり、根拠記録はS1〜S3で確定する（plan §4.1: 「path/hunkごとに移植先とownerを記録」）。一括採択/一括revertは禁止（ADR-0018 Decision 9）。

| path | 分類 | 仮採否 | 対応stage |
|---|---|---|---|
| `gradle/libs.versions.toml` | build | adopt(確定済み: anchor土台+fork追加) | S1(土台)として確定 |
| `lawnchair/res/drawable/ic_app_drawer.xml` | res | pending(S2/S3でanchor+fork merge方針を再確認) | S2/S3 |
| `lawnchair/res/values/strings.xml` | res | pending(S2/S3でanchor+fork merge方針を再確認) | S2/S3 |
| `lawnchair/src/app/lawnchair/AccentColorExtractor.java` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/BlankActivity.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/HeadlessWidgetsManager.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/LauncherActivityCachingLogic.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/NotificationManager.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/SearchBarStateHandler.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/AllAppsSearchInput.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/FallbackSearchInputView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchItemBackground.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultIcon.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultIconRow.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultRightLeftIcon.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultText.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/backup/ui/CreateBackupScreen.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/backup/ui/CreateBackupViewModel.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreNovaBackupScreen.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/bugreport/UploaderService.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/dagger/ForkBridgeModule.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/dagger/PreviewContextModule.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/folder/FolderEntity.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/folder/model/FolderViewModel.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/folder/service/FolderDao.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/folder/service/FolderService.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/iconoverride/IconOverride.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/iconoverride/IconOverrideRepository.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/wallpaper/model/WallpaperViewModel.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/data/wallpaper/service/WallpaperService.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/font/FontCache.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/font/FontManager.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/font/googlefonts/GoogleFontsListing.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/DirectionalGestureListener.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/GestureController.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/IconGestureListener.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/VerticalSwipeTouchController.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/config/GestureHandlerConfig.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/config/GestureHandlerOption.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/handlers/OpenAppDrawerGestureHandler.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/handlers/OpenAppSearchGestureHandler.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/handlers/OpenNotificationsHandler.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/handlers/RecentsGestureHandler.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/handlers/SleepGestureHandler.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/gestures/ui/CreateActionsScreen.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/homeedit/WorkspaceViewLookup.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/CustomIconPack.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/IconEntry.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/IconEntryWithDrawable.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/IconPack.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/IconPackProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/IconPickerCategory.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/IconPickerItem.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/LawnchairIconProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/SystemIconPack.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/shape/IconShape.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/icons/shape/IconShapeManager.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/nexuslauncher/OverlayCallbackImpl.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/nexuslauncher/SmartSpaceHostView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/nexuslauncher/ThemedSmartSpaceHostView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/override/CustomizeDialog.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/overview/TaskOverlayFactoryImpl.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/preferences/BasePreferenceManager.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/preferences/PreferenceAdapter.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/preferences/PreferenceManagerModule.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/preferences2/IdpPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/preferences2/PreferenceUtils.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/preferences2/ReloadHelper.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/qsb/AssistantIconView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/qsb/LawnQsbLayout.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/qsb/QsbIconUtil.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/qsb/providers/AppSearch.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/qsb/providers/QsbSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/qsb/providers/Startpage.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/LawnchairSearchAdapterProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/adapter/SearchAdapterItem.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/adapter/SearchTargetCompat.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/adapter/SearchTargetFactory.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/LawnchairAppSearchAlgorithm.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/LawnchairLocalSearchAlgorithm.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/LawnchairSearchAlgorithm.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/data/ContactInfo.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/SectionBuilder.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/ContactsSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/FileSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/SettingsSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/apps/AppSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/BuiltInWebSearchProviders.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/CustomWebSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/WebSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/WebSuggestionProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/DoubleShadowTextView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/IcuDateTextView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/PageIndicator.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/SmartspaceViewContainer.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/SmartspacerView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/model/SmartspaceCalendar.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/model/SmartspaceScores.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/model/SmartspaceTarget.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/provider/BatteryStatusProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/smartspace/provider/SmartspaceProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/theme/ThemeProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/theme/color/tokens/ColorStateListTokens.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/theme/color/tokens/ColorTokens.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/theme/drawable/DrawableTokens.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/popup/LawnchairShortcut.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/popup/WallpaperCarouselView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/PreferenceViewModel.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/Preferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/About.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/AboutModels.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/AboutViewModel.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/ChangesDialog.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/ContributorRow.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/GithubService.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/LawnchairLink.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/NightlyBuildsRepository.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/about/UpdateSection.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/AnnouncementPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/AppItem.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/FontPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/GridOverridesPreview.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/LauncherPreview.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/NavigationActionPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/NotificationDotsPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/OverlayHandlerPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/PermissionDialog.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/QuickActionsPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/SuggestionsPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/WallpaperPreview.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorContrastWarning.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorPreferenceModelList.kt` | model/data(lawnchair) | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorSelectionPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorSlider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/pickers/CustomColorPicker.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/pickers/PresetsList.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/pickers/SwatchGrid.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/ClickablePreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/ListPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/MainSwitchPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/PreferenceCategory.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/SliderPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/SwitchPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/TextPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/WarningPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/ClickableIcon.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/DividerColumn.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/LazyColumnPreferenceGroup.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/LoadingScreen.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/PreferenceGroup.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/PreferenceTemplate.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/TopBar.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/TwoTabPreferenceLayout.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/PositionalReorderer.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/ReorderHapticFeedback.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/ReorderablePreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/ReorderablePreferenceDefaults.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/DockSearchPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/DrawerSearchPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/FileSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/SearchProviderPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/WebSearchProvider.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/LiveInformationManager.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/LiveInformationRequest.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/LiveInformationService.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/SyncLiveInformation.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/AppDrawerFoldersPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/AppDrawerPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/CustomIconShapePreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/DebugMenuPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/DockPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/FeatureFlagsPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/FolderPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/FontSelectionPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/GeneralPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/GesturePreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HiddenAppsPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenGridPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/IconPackPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/IconPickerPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/IconShapePreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/PickAppForGesture.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/QuickstepPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SearchPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SearchProviderPreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SelectAppsForDrawerFolder.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SelectIconPreference.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SmartspacePreferences.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/theme/Shape.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/theme/Theme.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/theme/Type.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/util/LazyGridLayout.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/util/NavigationResult.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/util/ProvideBottomSheetHandler.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/ui/util/preview/PreferenceGroupPreviewContainer.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/AppCategorizationUtils.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/AppsList.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/Compatibility.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/DrawableUtils.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/FileAccessManager.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/FlowUtils.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/ImageViewWrapper.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/LawnchairUtils.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/LawnchairWindowManagerProxy.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/MainThreadInitializedObject.java` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/PackageManagerExtensions.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/util/RecentHelper.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/views/ComposeBottomSheet.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/views/FullScreenOverlayView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/views/LauncherPreviewView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/views/LawnchairFloatingSurfaceView.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/app/lawnchair/wallpaper/WallpaperManagerCompat.kt` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `lawnchair/src/com/google/android/libraries/launcherclient/LauncherClient.java` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `quickstep/dagger/com/android/launcher3/dagger/LauncherAppComponent.java` | model/data(dagger) | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `quickstep/src/com/android/quickstep/SystemUiProxy.java` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `quickstep/src/com/android/quickstep/logging/StatsLogCompatManager.java` | lawnchair-ui/organizer/homeedit | pending(S2でport) | S2 |
| `res/values/strings.xml` | res | pending(S2/S3でanchor+fork merge方針を再確認) | S2/S3 |
| `settings.gradle` | build | adopt(確定済み: anchor土台+fork追加) | S1(土台)として確定 |
| `specs/516-16-rebase-phase2/replay-log.md` | tests/tools/specs/docs | adopt(pending review) | S1〜S4 |
| `src/com/android/launcher3/DeviceProfile.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/LauncherAppState.java` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/LauncherAppState.kt` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/LauncherModel.java` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/LauncherModel.kt` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/LauncherSettings.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/ModelCallbacks.kt` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/Utilities.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/config/FeatureFlags.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/folder/FolderIcon.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/graphics/IconShape.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/graphics/LauncherPreviewRenderer.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/icons/IconCache.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/model/BgDataModel.java` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/model/BgDataModel.kt` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/model/GridSizeMigrationDBController.java` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/model/ModelUtils.java` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/model/ModelWriter.java` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/model/data/FolderInfo.java` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/preview/PreviewContext.kt` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/provider/LauncherDbUtils.kt` | model/data | pending(S1/S2/S3で再決定: anchor構造を正) | S1/S2/S3 |
| `src/com/android/launcher3/util/DaggerSingletonObject.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/util/MainThreadInitializedObject.java` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `src/com/android/launcher3/util/OnboardingPrefs.kt` | production-other | pending(S1〜S3で分類) | S1〜S3 |
| `wmshell/multivalentTestsForDevice` | build | adopt(確定済み: anchor土台+fork追加) | S1(土台)として確定 |
| `wmshell/multivalentTestsForDeviceless` | build | adopt(確定済み: anchor土台+fork追加) | S1(土台)として確定 |
