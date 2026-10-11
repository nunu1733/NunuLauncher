# Test Audit — Issue #574 ContactsSearchProvider Data Projection

> Status: proposed (local/diagnostic, CI route pending review)
> Scope: `tests/organizer-instrumentation/app/lawnchair/search/ContactsSearchProviderDataProjectionTest.kt` (3 methods)
> Oracle: Issue #574 comment 6108156834 (accepted oracle link)
> Head SHA: uncommitted change, no head SHA claimed

## Scope note

ONE bounded unit: this audit only. No CI route changes, no feature/test code changes, no doc/map edits, no broader audit oracle in this file.

## Fix context (main-provided)

- API 37 fake ContentProvider rejects Data `account_type`/`account_name` projection columns, causing search flow zero results.
- Fix: 10 deletions (remove only account columns; `phonebook_label` remains permitted per device probe 2026-10-11).
- New test: 3 instrumented tests via real ContentResolver fixture + public `ContactsSearchProvider.search` seam.
- RED: 3 tests, 1 fail on emulator-5554. GREEN: 3/3 on main 11:43:36 UTC, build 3m 56 s.

## 8 authoring-gate answers

1. **Protected contract** — Granted, pref-on people query must still emit one `SearchResult.Contact` when the Data view rejects `account_type`/`account_name` projections (API 37 CP2 behavior). API 36-style path (account columns allowed) and denied-permission zero-query short-circuit must not regress.
2. **Credible regression** — Re-adding `account_type`/`account_name` to the production projection makes the API-37-mode test fail with `IllegalArgumentException("Invalid column …")` instead of 1 contact (RED 1 fail observed on emulator-5554). Denied-path regression (query issued when denied) fails the `queryCount == 0` assert. Account-read API 36 path regression fails the allowed-mode 1-contact assert.
3. **Canonical owner** — This new class is the primary owner. Prior codegraph found no existing coverage of the contacts Data projection contract. No test add/removal or deduplication in this audit.
4. **Distinct higher-layer risk (why instrumentation, not pure JVM)** — Pure JVM cannot faithfully instantiate `android.content.ContentResolver` without a Robolectric dependency (no new deps per scope). The fixture emulates the platform Data-view runtime contract (reject forbidden projection, serve one synthetic row) behind a real `ContentResolver` dispatch, which is the credible runtime boundary here.
5. **Production seam cost** — No new prod seam. Test uses only the public `ContactsSearchProvider.search(context, query)` entry. No private `findContactsByName` call, no test-only production interface, no real contact seeding, no permission mutation (permission observed via wrapper `checkSelfPermission`).
6. **Impact ownership** — No current search instrumentation CI owner fits. Documented non-owners: existing `source_search` JVM filter `app.lawnchair.search.*` runs `tests/unit` only; shared-writer lane class list is not search; production-input lane is API 35 organizer integration, not contacts. This audit does NOT claim any existing lane automatically covers the new test.
7. **CI classification** — Diagnostic / local-only, route PENDING until review (skill rule: ownership unknown → keep local/diagnostic). No lane/surface edge proposed in this file because no documented ownership fits; no invent. `ci_portfolio_map.yml` and `ci-test-portfolio.md` unchanged.
8. **Cost versus confidence** — Test body ~0.2 s, 3 methods; real cost is emulator startup. Independent confidence: API 37 zero-results regression + API 36 allowed-path + denied zero-query preservation, none provable below the ContentResolver boundary.

## Assertion-truthfulness note

Existing asserts check `name`/`number`/`contactId` only. They do NOT check `photo` or `ACTION_VIEW`/tap. Main will add photo assert / tap real QA if needed; this file makes no such claim.

## Unresolved routing decision

CI lane/surface routing for `ContactsSearchProviderDataProjectionTest` is unresolved; test stays local/diagnostic until reviewer assigns or creates ownership. No map/doc edits made here.

## Verification update (2026-10-11, docs-only addendum)

- GREEN observed post-fix: emulator-5554 XML `2026-10-11T11:43:36` (3 tests,
  0 failures) and actual API 36 runtime 3/3 on emulator-5556. Detail:
  `green-result.md`. This file still makes no CI-execution claim.
- `docs/engineering/ci-test-portfolio.md` gained a diagnostic row and
  `tools/repo-contract/ci_portfolio_map.yml` gained a no-edge comment for this
  class in the same PR (coordination record only, no lane assigned).
- Classification stays diagnostic / local-only, owner pending. Status stays
  proposed; no accepted/implemented claim.
