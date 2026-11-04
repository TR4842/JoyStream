#!/usr/bin/env python3
"""Bump JoyStream's version and record the upstream MovieBox-Tui commit.

Usage:
    python3 tools/bump_version.py --sha <commit> --date <iso8601> [--commits file]

Updates ``version.properties`` (versionCode, versionName, upstreamSha,
upstreamDate) and appends an entry to ``UPSTREAM_CHANGELOG.md``.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "version.properties"
CHANGELOG_FILE = ROOT / "UPSTREAM_CHANGELOG.md"


def read_props(path: pathlib.Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def write_props(path: pathlib.Path, props: dict[str, str]) -> None:
    ordered = ["versionCode", "versionName", "upstreamSha", "upstreamDate"]
    lines = [
        "# Auto-maintained by .github/workflows/upstream-sync.yml — do not edit by hand.",
    ]
    for key in ordered:
        lines.append(f"{key}={props.get(key, '')}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def bump_patch(version: str) -> str:
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)", version.strip())
    if not match:
        return "1.0.0"
    major, minor, patch = (int(part) for part in match.groups())
    return f"{major}.{minor}.{patch + 1}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True, help="upstream commit sha")
    parser.add_argument("--date", default="", help="upstream commit date (ISO 8601)")
    parser.add_argument("--commits", default=None, help="path to a file with upstream commit lines")
    args = parser.parse_args()

    props = read_props(VERSION_FILE)
    current_code = int(props.get("versionCode") or 0)
    current_name = props.get("versionName") or "1.0.0"
    new_name = bump_patch(current_name)

    props["versionCode"] = str(current_code + 1)
    props["versionName"] = new_name
    props["upstreamSha"] = args.sha.strip()
    props["upstreamDate"] = args.date.strip() or datetime.now(timezone.utc).isoformat()
    write_props(VERSION_FILE, props)

    commits = ""
    if args.commits:
        path = pathlib.Path(args.commits)
        if path.exists():
            commits = path.read_text(encoding="utf-8").strip()

    if not CHANGELOG_FILE.exists():
        CHANGELOG_FILE.write_text(
            "# Upstream sync log\n\n"
            "Every entry corresponds to a JoyStream release generated automatically after\n"
            "`mesamirh/MovieBox-Tui` moved.\n\n",
            encoding="utf-8",
        )

    with CHANGELOG_FILE.open("a", encoding="utf-8") as handle:
        handle.write(f"## v{new_name} — {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC\n\n")
        handle.write(f"- Upstream commit: `{args.sha.strip()}`\n")
        if args.date:
            handle.write(f"- Upstream date: `{args.date.strip()}`\n")
        if commits:
            handle.write(f"\n{commits}\n")
        handle.write("\n")

    print(f"versionName={new_name}")
    print(f"versionCode={props['versionCode']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
