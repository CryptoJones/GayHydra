#!/usr/bin/env python3
"""
i18n apply — rewrite Java literals to I18n.tr(key) calls per a sweep TSV.

Reads stdin (or --tsv FILE) lines: file<TAB>line<TAB>literal<TAB>key

For each row:
- Reads the named file.
- On the named line, finds the FIRST occurrence of "literal" (with quotes).
- Replaces "literal" with I18n.tr("key").
- Appends key=literal to docking/messages.properties (dedup).
- Ensures the file imports generic.i18n.I18n.

Safety:
- Skips rows whose target line text does not contain the literal verbatim.
- Skips duplicate-key rows (different files producing same key).
- Reports skips to stderr; exits non-zero if any row fails.
- Idempotent: a file already converted to I18n.tr(key) for the row's
  literal is treated as "already applied" and the key is still recorded
  in messages.properties (in case of multi-call dedup).
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import OrderedDict
from pathlib import Path

MODULE_BUNDLE = Path(
    "Ghidra/Framework/Docking/src/main/resources/docking/messages.properties"
)
I18N_IMPORT = "import generic.i18n.I18n;"
LAST_IMPORT_PATTERN = re.compile(
    r"^import [^;]+;$", re.MULTILINE
)


def parse_tsv(stream) -> list[tuple[str, int, str, str]]:
    rows: list[tuple[str, int, str, str]] = []
    for raw in stream:
        line = raw.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 4:
            print(f"skip malformed row: {line!r}", file=sys.stderr)
            continue
        f, ln, lit, key = parts
        rows.append((f, int(ln), lit, key))
    return rows


def ensure_import(text: str) -> str:
    if I18N_IMPORT in text:
        return text
    matches = list(LAST_IMPORT_PATTERN.finditer(text))
    if not matches:
        pkg = re.search(r"^(package [^;]+;)$", text, re.MULTILINE)
        if not pkg:
            return text
        idx = pkg.end()
        return text[:idx] + "\n\n" + I18N_IMPORT + text[idx:]
    last = matches[-1]
    idx = last.end()
    return text[:idx] + "\n" + I18N_IMPORT + text[idx:]


def rewrite_file(path: Path, edits: list[tuple[int, str, str]]) -> tuple[int, int]:
    raw = path.read_text()
    lines = raw.split("\n")
    applied = 0
    skipped = 0
    for line_no, lit, key in edits:
        idx = line_no - 1
        if idx < 0 or idx >= len(lines):
            print(f"skip {path}:{line_no} — line out of range", file=sys.stderr)
            skipped += 1
            continue
        target = f'"{lit}"'
        replacement = f'I18n.tr("{key}")'
        if target not in lines[idx]:
            if replacement in lines[idx]:
                applied += 1
                continue
            print(
                f"skip {path}:{line_no} — literal {target!r} not on line",
                file=sys.stderr,
            )
            skipped += 1
            continue
        new_line = lines[idx].replace(target, replacement, 1)
        lines[idx] = new_line
        applied += 1
    if applied > 0:
        new_text = "\n".join(lines)
        new_text = ensure_import(new_text)
        path.write_text(new_text)
    return applied, skipped


def load_existing_bundle(bundle: Path) -> "OrderedDict[str, str]":
    entries: OrderedDict[str, str] = OrderedDict()
    if not bundle.exists():
        return entries
    for line in bundle.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        entries[k] = v
    return entries


def write_bundle(bundle: Path, header: list[str], entries: "OrderedDict[str, str]") -> None:
    out = list(header)
    if header and header[-1] != "":
        out.append("")
    out.append("# Auto-populated by scripts/i18n-apply.py. Keys are sorted.")
    out.append("")
    for k in sorted(entries.keys()):
        v = entries[k]
        v = v.replace("\\", "\\\\").replace("\n", "\\n")
        out.append(f"{k}={v}")
    bundle.write_text("\n".join(out) + "\n")


def read_bundle_header(bundle: Path) -> list[str]:
    if not bundle.exists():
        return []
    header = []
    for line in bundle.read_text().splitlines():
        if line.startswith("#") or not line.strip():
            header.append(line)
        else:
            break
    return header


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tsv", type=Path, help="TSV input (default: stdin)")
    ap.add_argument(
        "--bundle",
        type=Path,
        default=MODULE_BUNDLE,
        help="messages.properties to populate (default: docking module)",
    )
    args = ap.parse_args()

    stream = args.tsv.open() if args.tsv else sys.stdin
    rows = parse_tsv(stream)
    if args.tsv:
        stream.close()

    by_file: dict[Path, list[tuple[int, str, str]]] = {}
    keys: OrderedDict[str, str] = OrderedDict()
    dup_collisions = 0
    for f, ln, lit, key in rows:
        p = Path(f)
        by_file.setdefault(p, []).append((ln, lit, key))
        if key in keys:
            if keys[key] != lit:
                print(
                    f"COLLISION: key {key!r} already mapped to {keys[key]!r}; "
                    f"new attempt is {lit!r} from {f}:{ln}",
                    file=sys.stderr,
                )
                dup_collisions += 1
        else:
            keys[key] = lit

    total_applied = total_skipped = 0
    for path, edits in sorted(by_file.items()):
        applied, skipped = rewrite_file(path, edits)
        total_applied += applied
        total_skipped += skipped

    existing = load_existing_bundle(args.bundle)
    header = read_bundle_header(args.bundle)
    for k, v in keys.items():
        existing[k] = v
    write_bundle(args.bundle, header, existing)

    print(
        f"applied {total_applied}, skipped {total_skipped}, "
        f"collisions {dup_collisions}, bundle keys now {len(existing)}",
        file=sys.stderr,
    )
    return 0 if (total_skipped == 0 and dup_collisions == 0) else 1


if __name__ == "__main__":
    sys.exit(main())
