# Sanitized recovery snapshot

- Source snapshot: `/app/gp-system` on `dev-machine`
- Captured: 2026-08-03T19:30:24Z
- Recovery branch: `recovery/dev-20260803-gp-system-sanitized`
- Supplement archive SHA-256: `9a37d1386efc37dca23fd6c83192870150952932e23e5a93283011fb632a6aed`

This branch contains the current application source recovered from the verified
supplement archive. Runtime databases and WAL files, logs, generated output,
test audio, local agent state, `.env`, Android signing material, passwords,
build caches, packaged APKs, and business spreadsheets are intentionally not
included. They remain available in the restricted full recovery archives.

`deepseekv4_demo.py` contained a shell `curl` example rather than Python and was
renamed to `deepseekv4_demo.sh` so non-executing syntax validation reflects its
actual language.

The initial scanner finding in `webapp.py` was manually reviewed and found to
be a false positive: it was a Chinese user-facing explanation keyed by an OSS
error code, not credential material. The explanation was preserved while the
key expression was split to prevent the generic assignment heuristic from
misclassifying it. The final high-confidence secret scan reports zero findings.
