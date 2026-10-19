"""Tests of quality_ratchet.py (python3 -m unittest discover -s build-rules)."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import quality_ratchet

PMD_XML = """<?xml version="1.0" encoding="UTF-8"?>
<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.28.0">
<file name="A.java">
<violation beginline="1" rule="CognitiveComplexity" package="io.example" class="A">x</violation>
<violation beginline="2" rule="AvoidDuplicateLiterals" package="io.example" class="A">x</violation>
<violation beginline="3" rule="AvoidDuplicateLiterals" package="io.example" class="A">x</violation>
</file>
<file name="B.java">
<violation beginline="1" rule="NestedTernary" package="" class="B">x</violation>
</file>
</pmd>
"""
JACOCO_CSV = """GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED
m,io.example,A,0,0,1,2,1,99
m,io.example,B,0,0,2,1,2,98
"""


class QualityRatchetTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.module = Path(self.tmp.name)
        (self.module / "target/site/jacoco").mkdir(parents=True)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        (self.module / relative).write_text(text, encoding="utf-8")

    def test_coverage_is_rounded_down(self) -> None:
        self.write("target/site/jacoco/jacoco.csv", JACOCO_CSV)
        self.assertEqual(quality_ratchet.coverage(self.module), (0.98, 0.5))

    def test_coverage_of_nothing_is_complete(self) -> None:
        self.write("target/site/jacoco/jacoco.csv", JACOCO_CSV.splitlines()[0] + "\n")
        self.assertEqual(quality_ratchet.coverage(self.module), (1.0, 1.0))

    def test_baseline_lists_rules_per_class_and_counts_violations(self) -> None:
        self.write("target/pmd.xml", PMD_XML)
        self.assertEqual(quality_ratchet.write_baseline(self.module), 4)
        lines = (self.module / quality_ratchet.BASELINE).read_text(encoding="utf-8").splitlines()
        self.assertEqual([line for line in lines if not line.startswith("#")], ["B=NestedTernary", "io.example.A=AvoidDuplicateLiterals,CognitiveComplexity"])

    def test_baseline_is_removed_when_there_is_no_violation(self) -> None:
        self.write(quality_ratchet.BASELINE, "io.example.A=CognitiveComplexity\n")
        self.assertEqual(quality_ratchet.write_baseline(self.module), 0)
        self.assertFalse((self.module / quality_ratchet.BASELINE).exists())

    def test_check_reports_paid_debt(self) -> None:
        self.write("target/pmd.xml", PMD_XML)
        self.write(quality_ratchet.BASELINE, "# comment\nio.example.A=CognitiveComplexity,UnusedPrivateField\nio.example.Gone=CloseResource\n")
        self.assertEqual(quality_ratchet.stale(self.module), ["io.example.A=UnusedPrivateField", "io.example.Gone=CloseResource"])
        self.assertEqual(quality_ratchet.main(["check", str(self.module)]), 1)

    def test_check_passes_when_the_baseline_matches(self) -> None:
        self.write("target/pmd.xml", PMD_XML)
        quality_ratchet.write_baseline(self.module)
        self.assertEqual(quality_ratchet.main(["check", str(self.module)]), 0)

    def test_main_rejects_an_unknown_command(self) -> None:
        self.assertEqual(quality_ratchet.main(["lower", str(self.module)]), 2)

    def test_main_prints_coverage_and_baseline(self) -> None:
        self.write("target/site/jacoco/jacoco.csv", JACOCO_CSV)
        self.write("target/pmd.xml", PMD_XML)
        self.assertEqual(quality_ratchet.main(["coverage", str(self.module)]), 0)
        self.assertEqual(quality_ratchet.main(["baseline", str(self.module)]), 0)


class RuffTest(unittest.TestCase):
    def test_ruff_finds_no_violation(self) -> None:
        ruff = shutil.which("ruff")
        self.assertIsNotNone(ruff, "ruff is missing (brew install ruff, or pip install ruff): the quality gate cannot run")
        root = Path(__file__).resolve().parent.parent
        result = subprocess.run([str(ruff), "check", "--no-cache", str(root)], capture_output=True, text=True, check=False, cwd=root)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
