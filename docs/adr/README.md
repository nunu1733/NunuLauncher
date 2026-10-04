# Architecture Decision Records

ADRは、次の3条件をすべて満たす判断だけに使う。

1. 後から変更するcostが高い。
2. codeだけでは理由が意外または不明である。
3. 実際のtrade-offと代替案があった。

file名は `NNNN-short-slug.md` とし、最大番号の次を使う。本文は「何を、なぜ決めたか」を短く記載する。進捗、meeting note、一般的best practice、小さなlibrary選定には使わない。

statusは必要な場合だけ `proposed | accepted | deprecated | superseded by ADR-NNNN` を使う。判断を変更するときは元ADRを消さず、新ADRから置換関係を示す。
