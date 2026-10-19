#!/usr/bin/env python3
"""Ratchets of the quality gates (gluonify-plasma README, "Quality gates").

After `mvn verify -Dgluonify.coverage.minimum=0 -Dpmd.failOnViolation=false -Dgluonify.pmd.baseline=none` in a module
(no baseline: the PMD report then lists every violation, including the excluded ones):
  quality_ratchet.py coverage <module-dir>   prints the line and branch coverage rounded down (target/site/jacoco/jacoco.csv)
  quality_ratchet.py baseline <module-dir>   writes <module-dir>/pmd-baseline.properties from target/pmd.xml (class=Rule,Rule)
  quality_ratchet.py check <module-dir>      fails when the baseline lists a class or a rule that target/pmd.xml no longer reports
                                             (debt paid: remove the line so that it cannot come back)
Standard library only.
"""

import csv
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BASELINE = "pmd-baseline.properties"
HEADER = (
    "# Existing PMD debt of this module (gluonify-plasma build-rules/quality_ratchet.py baseline), checked by mvn verify.\n"
    "# One line per class: class=Rule,Rule. A line shrinks as the class is fixed; it is never added nor extended.\n"
)


def coverage(module: Path) -> tuple[float, float]:
    """Line and branch covered ratios of the module, rounded down to two decimals (1.0 when there is nothing to cover)."""
    counts = {"LINE": [0, 0], "BRANCH": [0, 0]}
    with (module / "target/site/jacoco/jacoco.csv").open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            for counter, total in counts.items():
                total[0] += int(row[f"{counter}_COVERED"])
                total[1] += int(row[f"{counter}_MISSED"])
    return _ratio(*counts["LINE"]), _ratio(*counts["BRANCH"])


def _ratio(covered: int, missed: int) -> float:
    total = covered + missed
    return 1.0 if total == 0 else math.floor(covered * 100 / total) / 100


def _violation_elements(module: Path) -> list[ET.Element]:
    report = module / "target/pmd.xml"
    if not report.exists():
        return []
    return [element for element in ET.parse(report).getroot().iter() if element.tag.rsplit("}", 1)[-1] == "violation"]


def violations(module: Path) -> dict[str, set[str]]:
    """Rules violated per class, keyed as maven-pmd-plugin reads its exclusion file (package.class)."""
    found: dict[str, set[str]] = {}
    for element in _violation_elements(module):
        package, cls = element.get("package", ""), element.get("class", "")
        key = f"{package}.{cls}" if package else cls
        found.setdefault(key, set()).add(element.get("rule", ""))
    return found


def read_baseline(module: Path) -> dict[str, set[str]]:
    path = module / BASELINE
    if not path.exists():
        return {}
    entries: dict[str, set[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            key, _, rules = line.partition("=")
            entries[key.strip()] = {rule.strip() for rule in rules.split(",") if rule.strip()}
    return entries


def write_baseline(module: Path) -> int:
    """Writes the baseline and returns the number of violations it covers."""
    found = violations(module)
    path = module / BASELINE
    if not found:
        path.unlink(missing_ok=True)
        return 0
    lines = [f"{key}={','.join(sorted(rules))}" for key, rules in sorted(found.items())]
    path.write_text(HEADER + "\n".join(lines) + "\n", encoding="utf-8")
    return len(_violation_elements(module))


def stale(module: Path) -> list[str]:
    found = violations(module)
    return [f"{key}={rule}" for key, rules in sorted(read_baseline(module).items()) for rule in sorted(rules - found.get(key, set()))]


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[0] not in {"coverage", "baseline", "check"}:
        print(__doc__, file=sys.stderr)
        return 2
    command, module = argv[0], Path(argv[1])
    if command == "coverage":
        line, branch = coverage(module)
        print(f"{line:.2f} {branch:.2f}")
    elif command == "baseline":
        print(write_baseline(module))
    else:
        gone = stale(module)
        for entry in gone:
            print(f"paid debt still in {BASELINE}: {entry}", file=sys.stderr)
        return 1 if gone else 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
