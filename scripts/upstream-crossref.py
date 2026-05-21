#!/usr/bin/env python3
"""
Generate docs/upstream-tracking/pr-issue-matches.md.

Walks all open PRs in NationalSecurityAgency/ghidra via GitHub GraphQL
and surfaces those that auto-close currently-open upstream issues
(via "Closes #N" / "Fixes #N" body references). Output is sorted by
issue thumbs-up count descending — highest-impact "work already
exists" candidates first.

Requires the `gh` CLI authenticated to GitHub.

See SprintHistory.md for context (this is the Sprint 2 refresh).
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

GRAPHQL = """
query($cursor: String) {
  repository(owner: "NationalSecurityAgency", name: "ghidra") {
    pullRequests(first: 100, after: $cursor, states: OPEN,
                 orderBy: {field: CREATED_AT, direction: DESC}) {
      pageInfo { endCursor hasNextPage }
      nodes {
        number title createdAt isDraft
        author { login }
        closingIssuesReferences(first: 10) {
          nodes { number title state reactions(content: THUMBS_UP) { totalCount } }
        }
        additions deletions changedFiles
      }
    }
  }
}
"""


def fetch_prs() -> list[dict]:
    prs: list[dict] = []
    cursor: str | None = None
    while True:
        args = ["gh", "api", "graphql", "-f", f"query={GRAPHQL}"]
        if cursor:
            args += ["-f", f"cursor={cursor}"]
        out = subprocess.check_output(args)
        data = json.loads(out)["data"]["repository"]["pullRequests"]
        prs.extend(data["nodes"])
        if not data["pageInfo"]["hasNextPage"]:
            break
        cursor = data["pageInfo"]["endCursor"]
    return prs


def build_matches(prs: list[dict]) -> list[dict]:
    matches: list[dict] = []
    for pr in prs:
        refs = (pr.get("closingIssuesReferences") or {}).get("nodes") or []
        for issue in refs:
            if issue.get("state") != "OPEN":
                continue
            matches.append({
                "pr_num": pr["number"],
                "pr_title": pr["title"],
                "pr_created": pr["createdAt"][:10],
                "pr_draft": pr["isDraft"],
                "pr_author": (pr.get("author") or {}).get("login", "?"),
                "pr_adds": pr["additions"],
                "pr_dels": pr["deletions"],
                "pr_files": pr["changedFiles"],
                "issue_num": issue["number"],
                "issue_title": issue["title"],
                "issue_upvotes": issue["reactions"]["totalCount"],
            })
    matches.sort(key=lambda m: (-m["issue_upvotes"], m["pr_adds"] + m["pr_dels"]))
    return matches


def render(prs: list[dict], matches: list[dict]) -> str:
    out: list[str] = []
    out.append("# Upstream NSA/ghidra: PR ↔ Issue matches\n\n")
    pr_count = len({m["pr_num"] for m in matches})
    issue_count = len({m["issue_num"] for m in matches})
    out.append(
        f"Snapshot: {len(prs)} open PRs scanned. {len(matches)} "
        f"PR→open-issue closing-references found, across {pr_count} "
        f"distinct PRs and {issue_count} distinct issues.\n\n"
    )
    out.append("Sorted by issue upvotes (desc), then by PR size (asc). "
               "Refreshed nightly by `.github/workflows/upstream-crossref-refresh.yml`.\n\n")
    out.append("| Issue 👍 | Issue | PR | Author | Size | Age |\n")
    out.append("|---|---|---|---|---|---|\n")
    for m in matches:
        out.append(
            f"| {m['issue_upvotes']} | "
            f"[#{m['issue_num']}](https://github.com/NationalSecurityAgency/ghidra/issues/{m['issue_num']}) "
            f"{m['issue_title']} | "
            f"[#{m['pr_num']}](https://github.com/NationalSecurityAgency/ghidra/pull/{m['pr_num']}) "
            f"{m['pr_title']}{' [DRAFT]' if m['pr_draft'] else ''} | "
            f"@{m['pr_author']} | "
            f"+{m['pr_adds']}/-{m['pr_dels']} | "
            f"{m['pr_created']} |\n"
        )
    return "".join(out)


def main() -> int:
    out_path = Path(__file__).resolve().parent.parent / "docs" / "upstream-tracking" / "pr-issue-matches.md"
    prs = fetch_prs()
    matches = build_matches(prs)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(render(prs, matches))
    print(f"wrote {len(matches)} matches across {len(prs)} PRs to {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
