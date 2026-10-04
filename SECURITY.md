# Security Policy

## Supported Versions

NunuLauncher is a fork of Lawnchair. This policy covers the NunuLauncher
`main` development line and builds produced from it. The product baseline for
that line is Lawnchair `v15.0.0-beta3.0`
([baseline commit](https://github.com/nunu1733/NunuLauncher/commit/505dbc40e6154c05158b5d0271c45f6a885a411b)).

| Version or build line | Supported |
| --- | --- |
| NunuLauncher `main` and builds from current `main` | :white_check_mark: |
| Other NunuLauncher branches or locally modified builds | :x: |
| Lawnchair upstream releases | See the [upstream security policy](https://github.com/LawnchairLauncher/lawnchair/security/policy) |

This fork does not claim support for Lawnchair releases or branches that are
not part of the NunuLauncher `main` line.

## Reporting Security Issues

To report a suspected vulnerability in NunuLauncher, use
[NunuLauncher private vulnerability reporting](https://github.com/nunu1733/NunuLauncher/security/advisories/new).
This is the primary private route for vulnerabilities in fork code, build or
configuration, release infrastructure, and other NunuLauncher-owned changes.

Please do not include sensitive details in public Issues, pull requests,
Telegram, or Discord. Include the affected build or commit, reproduction
steps, impact, and any minimal proof needed to reproduce the issue in the
private report.

If a report affects only unmodified Lawnchair upstream code and does not
affect NunuLauncher-specific changes or packaging, maintainers may coordinate
with the [upstream security policy](https://github.com/LawnchairLauncher/lawnchair/security/policy).
If you are unsure whether the issue is fork-specific, report it privately to
NunuLauncher first; do not route fork-specific issues to upstream by default.

We will keep security-report discussion and follow-up in the private advisory
workflow. No response-time or remediation-time guarantee is made by this
policy.
