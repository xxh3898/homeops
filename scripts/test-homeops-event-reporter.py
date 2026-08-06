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

    def test_retainsTransientRejection_forRetry(self):
        body = json.dumps({"eventKey": "retry-1"}).encode()
        path = REPORTER.write_spool("deployments", body)

        with mock.patch.object(REPORTER, "send", side_effect=REPORTER.urllib.error.HTTPError(
                "https://homeops.example.invalid", 429, "busy", {}, None)):
            with self.assertRaises(REPORTER.urllib.error.HTTPError):
                REPORTER.drain()

        self.assertTrue(path.exists())
        self.assertFalse((REPORTER.SPOOL_DIR / "quarantine").exists())

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

    @staticmethod
    def write_private(path, value):
        path.write_text(value, encoding="utf-8")
        path.chmod(0o600)


if __name__ == "__main__":
    unittest.main()
