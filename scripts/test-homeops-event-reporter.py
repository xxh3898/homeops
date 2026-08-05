#!/usr/bin/python3

import importlib.util
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

    @staticmethod
    def write_private(path, value):
        path.write_text(value, encoding="utf-8")
        path.chmod(0o600)


if __name__ == "__main__":
    unittest.main()
