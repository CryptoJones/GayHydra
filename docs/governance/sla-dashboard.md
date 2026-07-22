# Queue-health dashboard

Generated 2026-07-22 by `scripts/sla-dashboard.py`
(`.github/workflows/sla-dashboard.yml`, nightly). Metrics defined in
[PR_QUEUE_POLICY.md](PR_QUEUE_POLICY.md) / [TRIAGE_SLA.md](TRIAGE_SLA.md).

| Metric | Value |
|---|---|
| Open PRs | 8 |
| Open issues | 28 |
| Open PRs — lane:decomp-correctness | 1 |
| Open PRs — lane:framework | 7 |
| PRs missing any `triage:*` label | 8 |
| p50 / p90 days to first response (PRs, responded) | — / — |
| PRs still awaiting first response | 8 |
| p50 / p90 days to first response (issues, responded) | — / — |
| Issues still awaiting first response | 28 |
| Median age in `triage:accepted-for-review` (days) | — |
| `triage:needs-info` older than 30 days | 0 |

Queue-health gate (PR_QUEUE_POLICY.md): triggers when p90
days-to-first-response exceeds the SLA for two consecutive weeks —
compare against this file's git history.
