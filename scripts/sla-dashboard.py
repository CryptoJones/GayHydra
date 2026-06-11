#!/usr/bin/env python3
"""Generate docs/governance/sla-dashboard.md (the queue-health dashboard
PR_QUEUE_POLICY.md / TRIAGE_SLA.md promise).

Metrics, per the policy's list:
- open PR count by lane label
- p50 / p90 days to first non-author response (open PRs and issues)
- median age of items in triage:accepted-for-review
- count of PRs missing any triage:* label
- count of items in triage:needs-info older than 30 days

Run nightly by .github/workflows/sla-dashboard.yml (the crossref pattern);
requires GH_TOKEN. Deliberately simple: the GayHydra queue is small, so the
dashboard's value today is the *mechanism existing* — the queue-health gate
in PR_QUEUE_POLICY.md keys off these numbers.
"""

import json
import os
import statistics
import subprocess
import sys
from datetime import datetime, timezone

REPO = os.environ.get("REPO", "CryptoJones/GayHydra")
OUT = "docs/governance/sla-dashboard.md"


def gh(path, *args):
    cmd = ["gh", "api", path, "--paginate", *args]
    return json.loads(subprocess.check_output(cmd, text=True) or "[]")


def days_since(iso):
    then = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return (datetime.now(timezone.utc) - then).total_seconds() / 86400


def first_response_days(item, is_pr):
    """Days from open to the first comment/review by someone other than the
    author, or None if no response yet."""
    author = item["user"]["login"]
    number = item["number"]
    events = []
    for c in gh(f"repos/{REPO}/issues/{number}/comments"):
        if c["user"]["login"] != author:
            events.append(c["created_at"])
    if is_pr:
        for r in gh(f"repos/{REPO}/pulls/{number}/reviews"):
            if r["user"]["login"] != author and r.get("submitted_at"):
                events.append(r["submitted_at"])
    if not events:
        return None
    opened = datetime.fromisoformat(item["created_at"].replace("Z", "+00:00"))
    first = min(datetime.fromisoformat(e.replace("Z", "+00:00")) for e in events)
    return (first - opened).total_seconds() / 86400


def pctl(values, p):
    if not values:
        return "—"
    values = sorted(values)
    idx = min(len(values) - 1, round(p / 100 * (len(values) - 1)))
    return f"{values[idx]:.1f}"


def main():
    issues = gh(f"repos/{REPO}/issues?state=open&per_page=100")
    prs = [i for i in issues if "pull_request" in i]
    plain = [i for i in issues if "pull_request" not in i]

    lanes = {}
    missing_triage = 0
    needs_info_30d = 0
    accepted_ages = []
    for pr in prs:
        names = [l["name"] for l in pr["labels"]]
        lane = next((n for n in names if n.startswith("lane:")), "(no lane)")
        lanes[lane] = lanes.get(lane, 0) + 1
        if not any(n.startswith("triage:") for n in names):
            missing_triage += 1
    for item in issues:
        names = [l["name"] for l in item["labels"]]
        if "triage:needs-info" in names and days_since(item["created_at"]) > 30:
            needs_info_30d += 1
        if "triage:accepted-for-review" in names:
            accepted_ages.append(days_since(item["created_at"]))

    pr_resp = [first_response_days(p, True) for p in prs]
    issue_resp = [first_response_days(i, False) for i in plain]
    pr_responded = [d for d in pr_resp if d is not None]
    issue_responded = [d for d in issue_resp if d is not None]
    pr_waiting = sum(1 for d in pr_resp if d is None)
    issue_waiting = sum(1 for d in issue_resp if d is None)

    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    lines = [
        "# Queue-health dashboard",
        "",
        f"Generated {today} by `scripts/sla-dashboard.py`",
        "(`.github/workflows/sla-dashboard.yml`, nightly). Metrics defined in",
        "[PR_QUEUE_POLICY.md](PR_QUEUE_POLICY.md) / [TRIAGE_SLA.md](TRIAGE_SLA.md).",
        "",
        "| Metric | Value |",
        "|---|---|",
        f"| Open PRs | {len(prs)} |",
        f"| Open issues | {len(plain)} |",
    ]
    for lane in sorted(lanes):
        lines.append(f"| Open PRs — {lane} | {lanes[lane]} |")
    lines += [
        f"| PRs missing any `triage:*` label | {missing_triage} |",
        f"| p50 / p90 days to first response (PRs, responded) | {pctl(pr_responded, 50)} / {pctl(pr_responded, 90)} |",
        f"| PRs still awaiting first response | {pr_waiting} |",
        f"| p50 / p90 days to first response (issues, responded) | {pctl(issue_responded, 50)} / {pctl(issue_responded, 90)} |",
        f"| Issues still awaiting first response | {issue_waiting} |",
        f"| Median age in `triage:accepted-for-review` (days) | {pctl(accepted_ages, 50)} |",
        f"| `triage:needs-info` older than 30 days | {needs_info_30d} |",
        "",
        "Queue-health gate (PR_QUEUE_POLICY.md): triggers when p90",
        "days-to-first-response exceeds the SLA for two consecutive weeks —",
        "compare against this file's git history.",
        "",
    ]
    with open(OUT, "w") as f:
        f.write("\n".join(lines))
    print(f"wrote {OUT}")


if __name__ == "__main__":
    sys.exit(main())
