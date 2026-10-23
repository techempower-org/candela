"""#1866 — every GitHub Actions reference is pinned to an immutable commit, so a
moved or compromised tag can't change what CI runs.

Parses the workflows as YAML (job-level reusable workflows and step-level actions),
so quoted keys and flow mappings count and `uses:` text inside a `run:` script
doesn't. Needs PyYAML: system python3 on GitHub's ubuntu runners ships it.
Run: python3 tools/test_workflow_pins.py
"""

import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
SHA_PIN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")
DOCKER_PIN = re.compile(r"^docker://[^@\s]+@sha256:[0-9a-f]{64}$")


def action_refs(doc):
    """Every `uses:` value a workflow actually executes: jobs.<id>.uses and jobs.<id>.steps[].uses."""
    refs = []
    for job in ((doc or {}).get("jobs") or {}).values():
        if not isinstance(job, dict):
            continue
        if isinstance(job.get("uses"), str):
            refs.append(job["uses"])
        for step in job.get("steps") or []:
            if isinstance(step, dict) and isinstance(step.get("uses"), str):
                refs.append(step["uses"])
    return refs


def unpinned(ref):
    """None when [ref] is local or immutable, else why not."""
    if ref.startswith("./"):
        return None  # an action or workflow in this repo, reviewed with the code
    if ref.startswith("docker://"):
        return None if DOCKER_PIN.match(ref) else "docker image not pinned to a sha256 digest"
    return None if SHA_PIN.match(ref) else "not pinned to a 40-hex commit SHA"


class WorkflowPinTest(unittest.TestCase):
    def test_every_action_reference_is_immutable(self):
        seen = 0
        for wf in sorted(WORKFLOWS.glob("*.y*ml")):
            for ref in action_refs(yaml.safe_load(wf.read_text())):
                seen += 1
                why = unpinned(ref)
                self.assertIsNone(why, f"{wf.name}: `uses: {ref}`: {why}")
        self.assertGreater(seen, 0, "found no `uses:` at all; the test would be vacuous")

    def test_every_sha_pin_names_its_release_in_a_comment(self):
        # Dependabot rewrites the SHA and a `# vX.Y.Z` comment together. The comment is how a
        # reviewer reads which release runs. This checks it exists, not that the tag still matches.
        for wf in sorted(WORKFLOWS.glob("*.y*ml")):
            text = wf.read_text()
            for ref in action_refs(yaml.safe_load(text)):
                if SHA_PIN.match(ref):
                    self.assertRegex(text, re.escape(ref) + r"['\"]?\s+#\s*v\d+(\.\d+)*\b",
                                     f"{wf.name}: `{ref}` has no `# vX.Y.Z` release comment")

    def test_the_parser_sees_the_forms_a_regex_would_miss(self):
        doc = yaml.safe_load(
            'jobs:\n'
            '  a:\n'
            '    steps:\n'
            '      - "uses": actions/checkout@v5\n'
            '      - {uses: actions/setup-java@v4}\n'
            '      - run: |\n'
            '          echo "uses: not/an-action@v1"\n'
            '  b:\n'
            '    uses: org/repo/.github/workflows/x.yml@main\n')
        self.assertEqual(sorted(action_refs(doc)),
                         ["actions/checkout@v5", "actions/setup-java@v4", "org/repo/.github/workflows/x.yml@main"])
        self.assertIsNotNone(unpinned("actions/checkout@v5"))
        self.assertIsNone(unpinned("docker://alpine@sha256:" + "a" * 64))
        self.assertIsNotNone(unpinned("docker://alpine:3.20"))

    def test_dependabot_config_has_a_github_actions_update_entry(self):
        # Checks the parsed config has the entry; whether Dependabot runs it is GitHub's side.
        cfg = yaml.safe_load((ROOT / ".github" / "dependabot.yml").read_text())
        entries = [u for u in cfg.get("updates", []) if u.get("package-ecosystem") == "github-actions"]
        self.assertEqual(len(entries), 1, "expected exactly one github-actions update entry")
        self.assertEqual(entries[0].get("directory"), "/")
        self.assertIn("schedule", entries[0])


if __name__ == "__main__":
    unittest.main()
