#!/usr/bin/python3

import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "deploy" / "scripts" / "report-homeops-event.py"
SPEC = importlib.util.spec_from_file_location("homeops_event_reporter", SOURCE)
REPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORTER)


class HomeOpsEventReporterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="homeops-reporter.")
        root = pathlib.Path(self.temporary.name)
        REPORTER.APP_DIR = root / "app"
        REPORTER.SPOOL_DIR = root / "spool"
        REPORTER.APP_DIR.mkdir(mode=0o700)
        self.write_private(REPORTER.APP_DIR / "smoke.origin", "https://homeops.example.invalid:9443\n")
        self.write_private(REPORTER.APP_DIR / ".env",
                           "HOMEOPS_INGESTION_SHARED_SECRET=" + "a" * 64 + "\n")

    def tearDown(self):
        self.temporary.cleanup()

    def test_retains_then_drains_valid_event(self):
        body = json.dumps({"eventKey": "deploy-1"}).encode()
        path = REPORTER.write_spool("deployments", body)
        self.assertTrue(path.exists())

        with mock.patch.object(REPORTER, "send") as sender:
            REPORTER.drain()

        sender.assert_called_once_with(
            "https://homeops.example.invalid:9443", "a" * 64, "deployments", body)
        self.assertFalse(path.exists())

    def test_rejects_unsafe_kind_and_oversized_payload(self):
        with self.assertRaises(ValueError):
            REPORTER.validate_payload("commands", b'{"eventKey":"1"}')
        with self.assertRaises(ValueError):
            REPORTER.validate_payload("backups", b"x" * (REPORTER.MAX_PAYLOAD_BYTES + 1))

    def test_does_not_follow_redirect(self):
        handler = REPORTER.NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 307, None, None, None))

    def test_uses_current_account_home_for_default_paths(self):
        account = mock.Mock(pw_dir="/Users/another-account")

        with mock.patch.object(REPORTER.pwd, "getpwuid", return_value=account):
            app_dir, spool_dir = REPORTER.default_paths()

        self.assertEqual(pathlib.Path("/Users/another-account/Server/apps/homeops"), app_dir)
        self.assertEqual(pathlib.Path("/Users/another-account/Server/data/homeops/ingestion-spool"), spool_dir)

    def test_quarantinesPermanentRejection_andContinuesDrain(self):
        rejected = json.dumps({"eventKey": "reject-1"}).encode()
        accepted = json.dumps({"eventKey": "accept-1"}).encode()
        rejected_path = REPORTER.write_spool("deployments", rejected)
        accepted_path = REPORTER.write_spool("deployments", accepted)

        def sender(origin, secret, kind, body):
            if body == rejected:
                raise REPORTER.urllib.error.HTTPError(
                    "https://homeops.example.invalid", 409, "conflict", {}, None)

        with mock.patch.object(REPORTER, "send", side_effect=sender):
            REPORTER.drain()

        self.assertFalse(rejected_path.exists())
        self.assertFalse(accepted_path.exists())
        quarantined = list((REPORTER.SPOOL_DIR / "quarantine").glob("*.json"))
        self.assertEqual(1, len(quarantined))
        self.assertEqual(0o700, (REPORTER.SPOOL_DIR / "quarantine").stat().st_mode & 0o777)

    def test_quarantinesMalformedWrapper_andContinuesDrain(self):
        REPORTER.SPOOL_DIR.mkdir(mode=0o700)
        malformed_array = REPORTER.SPOOL_DIR / "000-malformed-array.json"
        malformed_array.write_text('[]', encoding="utf-8")
        malformed_array.chmod(0o600)
        malformed_body = REPORTER.SPOOL_DIR / "001-malformed-body.json"
        malformed_body.write_text('{"body":{}}', encoding="utf-8")
        malformed_body.chmod(0o600)
        body = json.dumps({"eventKey": "after-malformed"}).encode()
        accepted = REPORTER.SPOOL_DIR / "002-valid.json"
        accepted.write_text(json.dumps({"kind": "deployments", "body": body.decode()}), encoding="utf-8")
        accepted.chmod(0o600)

        with mock.patch.object(REPORTER, "send") as sender:
            REPORTER.drain()

        sender.assert_called_once_with(
            "https://homeops.example.invalid:9443", "a" * 64, "deployments", body)
        self.assertFalse(malformed_array.exists())
        self.assertFalse(malformed_body.exists())
        self.assertFalse(accepted.exists())
        self.assertEqual(2, len(list((REPORTER.SPOOL_DIR / "quarantine").glob("*.json"))))

    def test_quarantinesUnsafeEntries_andContinuesDrainWithoutReadingSymlinkTarget(self):
        REPORTER.SPOOL_DIR.mkdir(mode=0o700)
        unsafe_mode = REPORTER.SPOOL_DIR / "000-unsafe-mode.json"
        unsafe_mode.write_text('{"kind":"deployments","body":"unsafe"}', encoding="utf-8")
        unsafe_mode.chmod(0o644)
        sentinel = pathlib.Path(self.temporary.name) / "symlink-target"
        sentinel.write_text("must-not-be-read", encoding="utf-8")
        unsafe_link = REPORTER.SPOOL_DIR / "001-unsafe-link.json"
        unsafe_link.symlink_to(sentinel)
        unsafe_directory = REPORTER.SPOOL_DIR / "002-unsafe-directory.json"
        unsafe_directory.mkdir(mode=0o700)
        body = json.dumps({"eventKey": "after-unsafe"}).encode()
        accepted = REPORTER.SPOOL_DIR / "003-valid.json"
        accepted.write_text(json.dumps({"kind": "deployments", "body": body.decode()}), encoding="utf-8")
        accepted.chmod(0o600)

        with mock.patch.object(REPORTER, "MAX_DRAIN_FILES", 4), \
                mock.patch.object(REPORTER, "send") as sender:
            REPORTER.drain()

        sender.assert_called_once_with(
            "https://homeops.example.invalid:9443", "a" * 64, "deployments", body)
        self.assertEqual("must-not-be-read", sentinel.read_text(encoding="utf-8"))
        self.assertFalse(unsafe_mode.exists())
        self.assertFalse(unsafe_link.exists())
        self.assertFalse(unsafe_directory.exists())
        self.assertFalse(accepted.exists())
        self.assertEqual(3, len(list((REPORTER.SPOOL_DIR / "quarantine").glob("*.json"))))

    def test_retainsTransientRejection_forRetry(self):
        body = json.dumps({"eventKey": "retry-1"}).encode()
        path = REPORTER.write_spool("deployments", body)

        with mock.patch.object(REPORTER, "send", side_effect=REPORTER.urllib.error.HTTPError(
                "https://homeops.example.invalid", 429, "busy", {}, None)):
            with self.assertRaises(REPORTER.urllib.error.HTTPError):
                REPORTER.drain()

        self.assertTrue(path.exists())
        self.assertFalse((REPORTER.SPOOL_DIR / "quarantine").exists())

    def test_retainsUnavailableIngestionRoute_forRetry(self):
        for status in (404, 405):
            with self.subTest(status=status):
                body = json.dumps({"eventKey": f"unavailable-route-{status}"}).encode()
                path = REPORTER.write_spool("deployments", body)

                with mock.patch.object(REPORTER, "send", side_effect=REPORTER.urllib.error.HTTPError(
                        "https://homeops.example.invalid", status, "unavailable", {}, None)):
                    with self.assertRaises(REPORTER.urllib.error.HTTPError):
                        REPORTER.drain()

                self.assertTrue(path.exists())
                self.assertFalse((REPORTER.SPOOL_DIR / "quarantine").exists())
                path.unlink()

    def test_drainsRetainedEvent_when_drainOnlyModeRunsWithoutNewPayload(self):
        body = json.dumps({"eventKey": "retry-then-drain"}).encode()
        path = REPORTER.write_spool("deployments", body)

        with mock.patch.object(REPORTER, "send") as sender, \
                mock.patch.object(REPORTER.sys, "argv", ["report-homeops-event.py", "--drain"]), \
                mock.patch.object(REPORTER.sys, "stdin", mock.Mock(buffer=io.BytesIO())):
            REPORTER.main()

        sender.assert_called_once_with(
            "https://homeops.example.invalid:9443", "a" * 64, "deployments", body)
        self.assertFalse(path.exists())

    def test_doesNotExposeSpoolEntryBefore_atomicPublishCompletes(self):
        observed = []
        original_replace = REPORTER.os.replace

        def capture_replace(source, target):
            observed.append((pathlib.Path(source), pathlib.Path(target),
                             list(REPORTER.SPOOL_DIR.glob("*.json"))))
            original_replace(source, target)

        with mock.patch.object(REPORTER.os, "replace", side_effect=capture_replace):
            path = REPORTER.write_spool("deployments", json.dumps({"eventKey": "atomic-1"}).encode())

        pending, published, visible_entries = observed[0]
        self.assertFalse(pending.exists())
        self.assertEqual(path, published)
        self.assertEqual([], visible_entries)
        self.assertTrue(path.exists())

    def test_removesPendingSpoolEntry_when_atomicPublishFails(self):
        with mock.patch.object(REPORTER.os, "replace", side_effect=OSError("disk failure")):
            with self.assertRaises(OSError):
                REPORTER.write_spool("deployments", json.dumps({"eventKey": "atomic-2"}).encode())

        self.assertEqual([], list(REPORTER.SPOOL_DIR.glob("*.json")))
        self.assertEqual([], list(REPORTER.SPOOL_DIR.glob(".*.pending")))

    @staticmethod
    def write_private(path, value):
        path.write_text(value, encoding="utf-8")
        path.chmod(0o600)


if __name__ == "__main__":
    unittest.main()
