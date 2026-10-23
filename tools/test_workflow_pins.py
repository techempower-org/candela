"""#1866 — every GitHub Actions `uses:` is pinned to a commit SHA, so a moved or
compromised tag can't change what CI runs. Run: python3 tools/test_workflow_pins.py"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
USES = re.compile(r"(?m)^\s*(?:-\s*)?uses:\s*['\"]?([^\s'\"#]+)")
PINNED = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")


class WorkflowPinTest(unittest.TestCase):
    def test_every_third_party_action_is_pinned_to_a_commit(self):
        seen = 0
        for wf in sorted(WORKFLOWS.glob("*.y*ml")):
            for ref in USES.findall(wf.read_text()):
                if ref.startswith("./"):
                    continue  # a local action in this repo, reviewed with the code
                seen += 1
                self.assertRegex(ref, PINNED, f"{wf.name}: `uses: {ref}` is not pinned to a 40-hex commit SHA")
        self.assertGreater(seen, 0, "found no `uses:` at all; the test would be vacuous")

    def test_dependabot_keeps_the_pins_current(self):
        text = (ROOT / ".github" / "dependabot.yml").read_text()
        self.assertRegex(text, r'(?m)^\s*- package-ecosystem:\s*"github-actions"\s*$')


if __name__ == "__main__":
    unittest.main()
