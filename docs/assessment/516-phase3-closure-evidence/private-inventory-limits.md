# Private evidence inventory limits

Status: publication metadata only; original private files remain excluded from Git. No raw device capture or log content is reproduced here.

## Earlier inventory snapshot

`private-evidence-inventory.json` is a dated 2026-10-12 curation snapshot of 129 private files. It records the hashes and relative names of the originals while explicitly marking them excluded from publication. A hash-only comparison against the current private originals found 125 of 129 entries unchanged. Four session logs have since changed:

| Private relative path | Snapshot SHA-256 | Current private SHA-256 |
|---|---|---|
| `o1-ui/o1-session-logcat.txt` | `6a01f1bb91f594c6d840e019007f7b66065209f428bd76cf600d6af80a08b913` | `ab6d50a5afc334875c1ed7cdb5641e8e3ade64e21eb1db06720d987d466ef398` |
| `o1-ui/o1-talkback-logcat.txt` | `69ae973ffa1a7938b5df5b7451edbf6c3bfc900ebdacca698594d48ce9bad874` | `2912325e27fbe8e75ee271741fe5456bf2273d26c4a9dee743bc5ac235f16242` |
| `o2-bal/o2-session-logcat.txt` | `48a0e4db65f7f5c687a4302870d69f10cce5edcd6834b0f70ee84e2a50987daa` | `33b4dfcdf2e2bb3856d05fc00a9233003b3c53d20eb2263addd1747ed96edce1` |
| `o4-ops/o4-session-logcat.txt` | `7605e905c9d351d36eb19bd3ca16bd6afa18c55f1728724e6f4a71e62d31f6f0` | `0a8d6c66f620bdf709633fdb1f9acf30a05af9a4d28e440fff5b06ab4f66cf8c` |

These differences describe changes to private working files; they do not mean the originals were copied into the curated packet. Keep the earlier manifest as its historical snapshot. Do not overwrite it or describe it as a current byte-for-byte inventory.

The snapshot assigns the same SHA-256, `0611e8592278e6b0cff6aad0df59dcd66bf70d7a67bdb7a84479726bbd51e18b`, to both `o1-ui/o1-05-scenario1-landscape-edit-surface.png` and `o1-ui/o1-06-talkback-discard-notice.png`. The identical bytes mean the `o1-06` entry is not independent audible-notice evidence; it may be a duplicate or mislabeled copy. The new physical follow-up is the current narrow result: the owner reported hearing the notice once, while independent audio proof remains absent. See [physical discard-notice follow-up](physical-discard-notice.md).

## Selected follow-up inventory

`private-followup-inventory.json` covers 12 selected private CJKV and #579 notice artifacts under neutral aliases. Hash-only comparison found all 12 selected bytes match. The raw files remain private. This scoped index does not amend or claim to cover the earlier 129-file snapshot.
