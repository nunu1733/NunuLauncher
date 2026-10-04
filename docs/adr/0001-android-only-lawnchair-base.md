---
status: accepted
---

# Build an Android-only product on Lawnchair

NunuLauncherはAndroid専用とし、Launcher3を基盤とするLawnchairをforkして開発する。Androidではdefault home appとしてlayoutを所有でき、LawnchairのApache-2.0 code、標準grid、既存のcustomizationとbackupを活用できる一方、iOSはthird-party appがhome配置を変更する公開interfaceを提供しないためである。正確なLawnchair branch/tag/commitは再現buildと既存Deck layoutの評価後に別Issueで固定する。
