"""Tests for tools/candela-push (issue #1469). Run: python3 -m unittest tools/test_candela_push.py"""

import importlib.machinery
import importlib.util
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_PATH = Path(__file__).resolve().parent / "candela-push"
_loader = importlib.machinery.SourceFileLoader("candela_push", str(_PATH))
_spec = importlib.util.spec_from_loader("candela_push", _loader)
cp = importlib.util.module_from_spec(_spec)
_loader.exec_module(cp)

NOW = 10 * 24 * 60 * 60 * 1000
DAY = 24 * 60 * 60 * 1000


class RowIdTest(unittest.TestCase):
    def test_matches_java_name_uuid_pinned_in_kotlin_test(self):
        # Same constant as core-sync InboxSyncerTest — the phone and the
        # pusher must agree on the row id or pushes vanish silently.
        self.assertEqual(
            cp.inbox_row_id("11111111-2222-3333-4444-555555555555"),
            "4ab5af9e-fcbb-34d2-9edc-12a70bd4e082",
        )

    def test_is_version_3(self):
        self.assertEqual(cp.java_name_uuid("x")[14], "3")


class ParsingTest(unittest.TestCase):
    def test_http_url_detection(self):
        self.assertTrue(cp.is_http_url("https://example.com/a?b=c"))
        self.assertTrue(cp.is_http_url("HTTP://example.com"))
        self.assertFalse(cp.is_http_url("ftp://example.com"))
        self.assertFalse(cp.is_http_url("https://"))
        self.assertFalse(cp.is_http_url("https:///path"))
        self.assertFalse(cp.is_http_url("https://a b.com"))
        self.assertFalse(cp.is_http_url("example.com"))

    def test_single_url_argument_is_a_url_push(self):
        self.assertEqual(cp.classify_positional(["https://x.org/p"]), ("https://x.org/p", None))

    def test_words_are_a_text_push(self):
        self.assertEqual(cp.classify_positional(["read", "this", "https://x.org"]), (None, "read this https://x.org"))
        self.assertEqual(cp.classify_positional(["hello"]), (None, "hello"))

    def test_bare_url_is_shorthand_for_push(self):
        self.assertEqual(cp.normalize_argv(["https://x.org"]), ["push", "https://x.org"])
        self.assertEqual(cp.normalize_argv(["--dry-run", "hi"]), ["push", "--dry-run", "hi"])
        self.assertEqual(cp.normalize_argv(["list"]), ["list"])
        self.assertEqual(cp.normalize_argv([]), ["push"])


class MakeItemTest(unittest.TestCase):
    def test_url_item(self):
        i = cp.make_item(url=" https://x.org ", now_ms=NOW, source="katana", item_id="id1")
        self.assertEqual(i, {"id": "id1", "createdAt": NOW, "kind": "url", "url": "https://x.org", "from": "katana"})

    def test_text_item_with_title(self):
        i = cp.make_item(text="Hello", title="  T  ", now_ms=NOW)
        self.assertEqual((i["kind"], i["text"], i["title"]), ("text", "Hello", "T"))
        self.assertEqual(len(i["id"]), 36)

    def test_rejects_bad_input(self):
        for kwargs in (
            dict(url="ftp://x"),
            dict(text="   "),
            dict(text="x" * (cp.MAX_TEXT_CHARS + 1)),
            dict(url="https://x.org/" + "a" * cp.MAX_URL_CHARS),
            dict(text="ok", title="t" * (cp.MAX_TITLE_CHARS + 1)),
            dict(),
            dict(url="https://x.org", text="both"),
        ):
            with self.subTest(kwargs=list(kwargs)):
                with self.assertRaises(cp.PushError):
                    cp.make_item(now_ms=NOW, **kwargs)


class PayloadTest(unittest.TestCase):
    def test_decode_tolerates_garbage(self):
        empty = cp.decode_payload("not json")
        self.assertEqual(empty["items"], [])
        self.assertEqual(cp.decode_payload(None)["items"], [])
        self.assertEqual(cp.decode_payload('{"items":"nope"}')["items"], [])

    def test_decode_drops_malformed_items_and_keeps_unknown_fields(self):
        p = cp.decode_payload('{"v":2,"x":1,"items":[{"id":"a","kind":"url"},{"kind":"no-id"},3]}')
        self.assertEqual([i["id"] for i in p["items"]], ["a"])
        self.assertEqual((p["v"], p["x"]), (2, 1))

    def test_append_dedupes_prunes_old_and_stamps(self):
        old = {"id": "old", "kind": "url", "url": "https://o", "createdAt": NOW - 31 * DAY}
        keep = {"id": "keep", "kind": "url", "url": "https://k", "createdAt": NOW - DAY}
        new = {"id": "keep", "kind": "url", "url": "https://k2", "createdAt": NOW}
        out = cp.append_item({"v": 1, "items": [old, keep]}, new, NOW)
        self.assertEqual(out["items"], [new])
        self.assertEqual(out["updatedAt"], NOW)
        self.assertEqual(out["v"], 1)

    def test_append_keeps_a_future_version_number(self):
        out = cp.append_item({"v": 3, "items": []}, {"id": "a", "createdAt": NOW}, NOW)
        self.assertEqual(out["v"], 3)

    def test_prune_drops_oldest_until_under_size_cap_but_keeps_newest(self):
        items = [{"id": str(n), "kind": "text", "text": "x" * 100, "createdAt": NOW - n} for n in range(10)]
        kept = cp.prune(items, NOW, max_bytes=600)
        self.assertLess(len(kept), 10)
        self.assertEqual(kept[-1]["id"], "0")  # newest survives
        self.assertLessEqual(len(cp.encode_payload({"v": 1, "items": kept, "updatedAt": NOW})), 600)
        one = cp.prune(items[:1], NOW, max_bytes=10)
        self.assertEqual(len(one), 1)

    def test_encoded_payload_matches_kotlin_field_names(self):
        item = cp.make_item(url="https://x.org", now_ms=NOW, item_id="a")
        blob = json.loads(cp.encode_payload(cp.append_item(cp.decode_payload(None), item, NOW)))
        self.assertEqual(set(blob), {"v", "items", "updatedAt"})
        self.assertEqual(set(blob["items"][0]), {"id", "kind", "url", "createdAt"})


class ConfigTest(unittest.TestCase):
    def test_config_is_written_owner_only(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "sub" / "push.json"
            with mock.patch.dict(os.environ, {"CANDELA_PUSH_CONFIG": str(path)}):
                cp.save_config({"refreshToken": "secret"})
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                self.assertEqual(cp.load_config()["refreshToken"], "secret")

    def test_app_id_precedence(self):
        with mock.patch.dict(os.environ, {"CANDELA_APP_ID": "env"}, clear=False):
            self.assertEqual(cp.resolve_app_id("flag", {"appId": "cfg"}), "flag")
            self.assertEqual(cp.resolve_app_id(None, {"appId": "cfg"}), "env")


class PushFlowTest(unittest.TestCase):
    """End-to-end cmd_push against a fake InstantDB (no network)."""

    def test_push_appends_and_reads_back(self):
        store = {}

        def fake_post(path, body, headers=None):
            if path.startswith("/admin/query"):
                row_id = body["query"]["blobs"]["$"]["where"]["id"]
                return {"blobs": [store[row_id]] if row_id in store else []}
            if path.startswith("/admin/transact"):
                _, entity, row_id, attrs = body["steps"][0]
                self.assertEqual(entity, "blobs")
                self.assertEqual(headers["as-token"], "rt")
                store[row_id] = attrs
                return {}
            raise AssertionError(path)

        cfg = {"appId": "app", "userId": "u-1", "refreshToken": "rt"}
        args = cp.build_parser().parse_args(["push", "https://x.org/a", "--from", "test"])
        with mock.patch.object(cp, "post", fake_post), mock.patch.object(cp, "load_config", lambda: cfg):
            self.assertEqual(cp.cmd_push(args), 0)
            self.assertEqual(cp.cmd_push(cp.build_parser().parse_args(["push", "--text", "hi"])), 0)

        row = store[cp.inbox_row_id("u-1")]
        payload = json.loads(row["payload"])
        self.assertEqual([i["kind"] for i in payload["items"]], ["url", "text"])
        self.assertEqual(row["updatedAt"], payload["updatedAt"])
        # core-sync/instant.perms.json allows a row only when auth.id ==
        # data.userId, and core-sync/instant.attrs.json lists the only
        # attrs a fresh app has. The pusher must write exactly those.
        self.assertEqual(row["userId"], "u-1")
        attrs_file = Path(__file__).resolve().parent.parent / "core-sync" / "instant.attrs.json"
        allowed = set(json.loads(attrs_file.read_text())["blobs"])
        self.assertEqual(set(row), allowed)


if __name__ == "__main__":
    unittest.main()
