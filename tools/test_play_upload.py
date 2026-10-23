"""Tests for tools/play-upload (#1456). Run: python3 -m unittest tools/test_play_upload.py"""

import importlib.machinery
import importlib.util
import json
import re
import unittest
from pathlib import Path

_PATH = Path(__file__).resolve().parent / "play-upload"
_loader = importlib.machinery.SourceFileLoader("play_upload", str(_PATH))
_spec = importlib.util.spec_from_loader("play_upload", _loader)
pu = importlib.util.module_from_spec(_spec)
_loader.exec_module(pu)

PKG = "org.techempower.candela"
BASE = f"{pu.API}/{PKG}"

CHANGELOG = """# Changelog

## [Unreleased]

## [1.17.0] -- 2026-10-19

**Infernal Beacon.** Fifteen new places to read from.

### Added
- **Fifteen new sources**, all off by default (#1856). See
  [the list](https://example.org/x).
- **For you is one tap from the Library** (#1852).

### Fixed
- The rail fits short screens (#1839).

## [1.16.0] -- 2026-09-29

**Valiant Beacon.** Local-only.
"""


class FakeHttp:
    """Records every call; answers like the Play Developer API v3."""

    def __init__(self, version_code=279, existing=None):
        self.calls = []
        self.version_code = version_code
        self.existing = existing if existing is not None else []

    def __call__(self, method, url, headers=None, body=None):
        self.calls.append((method, url, headers or {}, body))
        if method == "POST" and url == f"{BASE}/edits":
            return 200, {"id": "E1"}
        if method == "POST" and url.startswith(f"{pu.UPLOAD}/{PKG}/edits/E1/bundles"):
            return 200, {"versionCode": self.version_code, "sha256": "abc"}
        if method == "GET" and url == f"{BASE}/edits/E1/tracks/alpha":
            return 200, {"track": "alpha", "releases": self.existing}
        if method == "PUT" and url == f"{BASE}/edits/E1/tracks/alpha":
            return 200, json.loads(body)
        if method == "POST" and url.startswith(f"{BASE}/edits/E1:validate"):
            return 200, {"id": "E1"}
        if method == "POST" and url.startswith(f"{BASE}/edits/E1:commit"):
            return 200, {"id": "E1"}
        if method == "DELETE" and url == f"{BASE}/edits/E1":
            return 204, {}
        return 404, {"error": {"message": f"unexpected {method} {url}"}}

    def steps(self):
        out = []
        for method, url, _, _ in self.calls:
            tail = url.split(f"/{PKG}", 1)[1].split("?", 1)[0]
            out.append(f"{method} {tail}")
        return out

    def put_body(self):
        return json.loads(next(b for m, _, _, b in self.calls if m == "PUT"))


def publish(http, **kw):
    args = dict(token="T", package=PKG, aab=b"AAB", version_code=279, version_name="1.16.0",
                track="alpha", status="draft", notes="Local-only build; cloud sync off", dry_run=True)
    args.update(kw)
    return pu.publish(http, **args)


class PublishTest(unittest.TestCase):
    def test_dry_run_validates_and_deletes_the_edit_and_never_commits(self):
        http = FakeHttp()
        out = publish(http, dry_run=True)
        self.assertEqual(http.steps(), [
            "POST /edits", "POST /edits/E1/bundles", "GET /edits/E1/tracks/alpha",
            "PUT /edits/E1/tracks/alpha", "POST /edits/E1:validate", "DELETE /edits/E1"])
        self.assertFalse(any(":commit" in u for _, u, _, _ in http.calls))
        self.assertEqual(out["result"], "validated (dry run, edit deleted)")
        self.assertEqual(out["trackBefore"], [], "the dry run reports what was already on the track")
        self.assertEqual(out["versionCode"], 279)

    def test_a_real_run_commits_and_can_skip_sending_for_review(self):
        http = FakeHttp()
        publish(http, dry_run=False, not_sent_for_review=True)
        commit = [u for m, u, _, _ in http.calls if ":commit" in u]
        self.assertEqual(len(commit), 1)
        self.assertIn("changesNotSentForReview=true", commit[0])
        self.assertNotIn("DELETE /edits/E1", http.steps())

    def test_the_new_release_is_a_draft_with_notes_and_the_live_release_is_kept(self):
        live = {"name": "274-5 (1.14.1)", "versionCodes": ["274"], "status": "completed"}
        old_draft = {"name": "x", "versionCodes": ["270"], "status": "draft"}
        http = FakeHttp(existing=[live, old_draft])
        publish(http)
        rel = http.put_body()["releases"]
        self.assertIn(live, rel, "the completed release on the track must survive the update")
        self.assertNotIn(old_draft, rel, "an older release with the same status is replaced")
        new = [r for r in rel if r["versionCodes"] == ["279"]][0]
        self.assertEqual(new["status"], "draft")
        self.assertEqual(new["name"], "279 (1.16.0)")
        self.assertEqual(new["releaseNotes"], [{"language": "en-US", "text": "Local-only build; cloud sync off"}])

    def test_a_version_code_mismatch_aborts_and_deletes_the_edit(self):
        http = FakeHttp(version_code=274)
        with self.assertRaises(pu.PlayError) as e:
            publish(http, version_code=279)
        self.assertIn("274", str(e.exception))
        self.assertEqual(http.steps()[-1], "DELETE /edits/E1")
        self.assertFalse(any(m == "PUT" for m, _, _, _ in http.calls))

    def test_an_api_error_deletes_the_edit_and_reports_the_message(self):
        class Failing(FakeHttp):
            def __call__(self, method, url, headers=None, body=None):
                if method == "PUT":
                    self.calls.append((method, url, headers or {}, body))
                    return 403, {"error": {"message": "The caller does not have permission"}}
                return super().__call__(method, url, headers, body)
        http = Failing()
        with self.assertRaises(pu.PlayError) as e:
            publish(http)
        self.assertIn("does not have permission", str(e.exception))
        self.assertEqual(http.steps()[-1], "DELETE /edits/E1")

    def test_every_call_carries_the_bearer_token_and_the_bundle_is_octet_stream(self):
        http = FakeHttp()
        publish(http)
        self.assertTrue(all(h.get("Authorization") == "Bearer T" for _, _, h, _ in http.calls))
        up = next(c for c in http.calls if "/bundles" in c[1])
        self.assertEqual(up[2].get("Content-Type"), "application/octet-stream")
        self.assertIn("uploadType=media", up[1])
        self.assertEqual(up[3], b"AAB")

    def test_only_draft_or_completed_status(self):
        with self.assertRaises(ValueError):
            publish(FakeHttp(), status="inProgress")


class NotesTest(unittest.TestCase):
    def test_notes_come_from_the_tag_section_as_plain_text(self):
        notes = pu.release_notes(CHANGELOG, "1.17.0")
        self.assertTrue(notes.startswith("Infernal Beacon. Fifteen new places to read from."))
        # each item is its bold headline (or first sentence), so the whole release fits in 500 chars
        self.assertIn("• Fifteen new sources\n• For you is one tap from the Library\n• The rail fits short screens.", notes)
        self.assertNotIn("**", notes)
        self.assertNotIn("](", notes)
        self.assertNotIn("#1856", notes, "issue refs mean nothing to testers")
        self.assertNotIn("Valiant", notes, "stops at the next version")

    def test_notes_fit_plays_500_character_limit(self):
        long = "## [9.9.9] -- 2026-01-01\n\n" + "\n".join(f"- Item number {i} with some words." for i in range(60))
        notes = pu.release_notes(long, "9.9.9")
        self.assertLessEqual(len(notes), 500)
        self.assertTrue(notes.endswith("…"))

    def test_a_missing_section_is_an_error(self):
        with self.assertRaises(pu.PlayError):
            pu.release_notes(CHANGELOG, "0.0.1")


class TokenTest(unittest.TestCase):
    def test_jwt_grant_signs_with_openssl_and_never_puts_the_key_on_argv(self):
        seen = {}

        def sign(data, key_pem):
            seen["data"], seen["key"] = data, key_pem
            return b"SIG"

        def http(method, url, headers=None, body=None):
            seen["url"], seen["body"] = url, body
            return 200, {"access_token": "AT", "expires_in": 3600}

        sa = {"client_email": "svc@p.iam.gserviceaccount.com", "private_key": "placeholder-not-a-key",
              "token_uri": "https://oauth2.googleapis.com/token"}
        tok = pu.mint_token(sa, now=1000, sign=sign, http=http)
        self.assertEqual(tok, "AT")
        self.assertEqual(seen["url"], "https://oauth2.googleapis.com/token")
        header, claims = [json.loads(pu._b64d(p)) for p in seen["data"].decode().split(".")]
        self.assertEqual(header, {"alg": "RS256", "typ": "JWT"})
        self.assertEqual(claims["scope"], "https://www.googleapis.com/auth/androidpublisher")
        self.assertEqual(claims["iss"], sa["client_email"])
        self.assertEqual(claims["exp"] - claims["iat"], 3600)
        self.assertIn(b"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer", seen["body"])

    def test_openssl_sign_passes_the_key_on_stdin(self):
        captured = {}

        def run(argv, input=None, capture_output=None, check=None, **kw):
            captured["argv"], captured["input"] = argv, input
            class R:
                stdout = b"SIG"
            return R()

        pu.openssl_sign(b"data", "PEMKEY", run=run)
        self.assertNotIn("PEMKEY", " ".join(captured["argv"]))
        self.assertIn(b"PEMKEY", captured["input"])

    def test_an_openssl_failure_is_a_play_error_with_its_message(self):
        import subprocess

        def run(argv, **kw):
            raise subprocess.CalledProcessError(1, argv, stderr=b"unable to load key")
        with self.assertRaises(pu.PlayError) as e:
            pu.openssl_sign(b"x", "K", run=run)
        self.assertIn("unable to load key", str(e.exception))

    def test_openssl_sign_refuses_oversized_input(self):
        with self.assertRaises(pu.PlayError):
            pu.openssl_sign(b"x" * (pu.SIGN_MAX + 1), "K", run=lambda *a, **k: None)

    def test_openssl_sign_passes_an_fd_above_stdio_and_closes_it(self):
        import os
        seen = {}

        def run(argv, input=None, capture_output=None, check=None, pass_fds=()):
            seen["fd"] = int(argv[-1].rsplit("/", 1)[1])
            seen["pass"] = pass_fds
            seen["data"] = os.read(seen["fd"], 100)
            class R:
                stdout = b"SIG"
            return R()

        pu.openssl_sign(b"hello", "K", run=run)
        self.assertGreaterEqual(seen["fd"], 3)
        self.assertEqual(seen["pass"], (seen["fd"],))
        self.assertEqual(seen["data"], b"hello")
        with self.assertRaises(OSError):
            os.fstat(seen["fd"])  # closed afterwards


WORKFLOWS = Path(__file__).resolve().parent.parent / ".github" / "workflows"


def _jobs(text):
    """Split a workflow into {job id: job text} (stdlib only: jobs are 2-space keys under `jobs:`)."""
    body = text.split("\njobs:\n", 1)[1]
    jobs, name, buf = {}, None, []
    for line in body.splitlines():
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if m:
            if name:
                jobs[name] = "\n".join(buf)
            name, buf = m.group(1), []
        elif name:
            buf.append(line)
    if name:
        jobs[name] = "\n".join(buf)
    return jobs


class SecretScopeTest(unittest.TestCase):
    """Regression check for the scoping of the Play upload key in CI (2026-10-23).

    Not the access boundary (that is the tag-only `play-upload` environment). This
    fails CI if a change starts handing PLAY_* secrets to a job outside it.
    """

    # Every way a workflow can name a PLAY_* secret, or hand over all secrets at once.
    PLAY_REF = re.compile(r"secrets\s*(?:\.\s*PLAY_|\[\s*['\"]PLAY_)", re.I)
    ALL_SECRETS = re.compile(r"toJSON\s*\(\s*secrets\s*\)|secrets\s*:\s*inherit", re.I)
    # A job-level property sits at exactly four spaces; a `run:` script line is deeper.
    ENV_PROP = re.compile(r"^    environment:\s*['\"]?play-upload['\"]?\s*$", re.M)

    def test_no_workflow_reads_play_secrets_at_workflow_level(self):
        for wf in sorted(WORKFLOWS.glob("*.y*ml")):
            header = wf.read_text().split("\njobs:\n", 1)[0]
            self.assertFalse(self.PLAY_REF.search(header) or self.ALL_SECRETS.search(header),
                             f"{wf.name}: secrets referenced above `jobs:`")

    def test_play_secrets_are_read_only_by_jobs_in_the_play_upload_environment(self):
        seen = 0
        for wf in sorted(WORKFLOWS.glob("*.y*ml")):
            for job, text in _jobs(wf.read_text()).items():
                names_env = bool(self.ENV_PROP.search(text))
                if self.ALL_SECRETS.search(text):
                    self.assertFalse(names_env, f"{wf.name}:{job} hands all secrets out inside play-upload")
                if not self.PLAY_REF.search(text):
                    continue
                seen += 1
                self.assertTrue(names_env,
                                f"{wf.name}:{job} reads a PLAY_* secret outside the play-upload environment")
        self.assertGreater(seen, 0, "no job reads the PLAY_* secrets; the guard would be vacuous")

    def test_only_the_play_upload_job_names_the_environment(self):
        named = [f"{wf.name}:{job}" for wf in sorted(WORKFLOWS.glob("*.y*ml"))
                 for job, text in _jobs(wf.read_text()).items() if self.ENV_PROP.search(text)]
        self.assertEqual(named, ["android.yml:play-upload"])

    def test_the_play_upload_job_only_runs_on_version_tag_pushes(self):
        job = _jobs((WORKFLOWS / "android.yml").read_text())["play-upload"]
        self.assertRegex(job, r"(?m)^    if: github\.event_name == 'push' && startsWith\(github\.ref, 'refs/tags/v'\)$")
        self.assertRegex(job, r"(?m)^      contents: read$")


if __name__ == "__main__":
    unittest.main()
