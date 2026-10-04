# Issue #231 device evidence

Representative evidence for the verified-success result surface (Issue #231, spec `specs/231-applied-result-outcome/spec.md` AC-8). Captured on the local emulator (`issue209_pixel_7_pro` AVD, Android 16 / API 36, `app.lawnchair.debug` build at head `6704cfe077` + implementation commit) by driving the real Organizer flow: Settings → Home screen → Organize home layout → Review organization → Apply.

| File | Locale | What it shows |
|---|---|---|
| `en-01-preview-future-tense.png` | en-US | The confirmation surface **before** applying: proposal wording (`4 placements will move` / `11 placements will be preserved`) is correct here and unchanged by this Issue. |
| `en-02-applied-result-past-tense.png` | en-US | The verified-success result surface **after** applying: success heading followed by the completed-tense applied counts (`4 placements moved` / `11 placements were preserved` / `0 new folders were created` / `0 new home screen pages were created`). No future-tense count lines remain. Row order (counts interleaved with reason breakdowns, then constraints) is unchanged from the previous surface. |
| `en-03-applied-result-after-home-roundtrip.png` | en-US | The same surface after leaving to Home and revisiting the Organizer: the completed-tense counts persist identically (AC-3). |
| `ja-01-applied-result-past-tense.png` | ja-JP | Same verified-success surface in Japanese: `移動した配置: 4件` / `保持した配置: 11件` / `作成した新しいフォルダ: 0個` / `作成した新しいホーム画面ページ: 0枚` (new ja plurals resolve; no English fallback). |

Note: during capture the emulator's System UI threw transient ANR dialogs while applying the locale switch (`stop`/`start` cycle). These are pre-existing emulator environment behavior unrelated to the launcher build; the launcher process itself did not crash and the flow completed.
