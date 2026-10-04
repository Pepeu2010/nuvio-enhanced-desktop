# Nuvio Enhanced Desktop

Independent, unofficial native fork of NuvioMedia/NuvioDesktop. No endorsement
by NuvioMedia is claimed. Upstream copyright, GPL-3.0 license and third-party
notices remain applicable. The upstream README below is retained as reference.

Project specification, audit, baseline results and milestone status:
https://github.com/Pepeu2010/nuvio-enhanced

Baseline: `25390defed025a871f6a3239427b4507f2fafb35` (upstream Dev).
Foundation changes dated 2026-10-04: independent packaging/data/cache/Windows
WebView2/shortcuts, own update repository, crash reports off by default, and
redaction of sensitive addon diagnostic URLs and Sentry text/breadcrumb data.
Existing authentication/sync/addon transport contracts are preserved.

Baseline MSI generated. Baseline full suite: 1402 tests, 26 failed, 1 skipped.
These inherited failures remain tracked; this fork is not yet a validated product
release. Real login/sync/playback and independent installation QA remain pending.
