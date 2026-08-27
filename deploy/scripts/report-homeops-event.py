#!/usr/bin/python3

import datetime
import decimal
import fcntl
import hashlib
import hmac
import json
import os
import pathlib
import pwd
import re
import ssl
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_PAYLOAD_BYTES = 16 * 1024
MAX_DRAIN_FILES = 3
MAX_SPOOL_ENTRIES = 128
TIMEOUT_SECONDS = 2


def account_home():
    return pathlib.Path(pwd.getpwuid(os.getuid()).pw_dir)


def default_paths():
    server_root = account_home() / "Server"
    return server_root / "apps" / "homeops", server_root / "data" / "homeops" / "ingestion-spool"


APP_DIR, SPOOL_DIR = default_paths()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


class SpoolCapacityError(ValueError):
    pass


def private_file(path):
    details = path.lstat()
    if not stat.S_ISREG(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise ValueError(f"{path.name} must be a regular non-symlink file")
    if details.st_uid != os.getuid() or stat.S_IMODE(details.st_mode) != 0o600:
        raise ValueError(f"{path.name} owner or mode is invalid")
    return path.read_text(encoding="utf-8").strip()


def private_directory(path):
    details = path.lstat()
    if not stat.S_ISDIR(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise ValueError(f"{path.name} must be a directory")
    if details.st_uid != os.getuid() or stat.S_IMODE(details.st_mode) != 0o700:
        raise ValueError(f"{path.name} owner or mode is invalid")


def quarantine_directory():
    path = SPOOL_DIR / "quarantine"
    path.mkdir(mode=0o700, exist_ok=True)
    private_directory(path)
    return path


def quarantine(path):
    target = quarantine_directory() / path.name
    if target.exists():
        target = target.with_stem(f"{target.stem}-{uuid.uuid4().hex}")
    path.rename(target)


def permanently_rejected(error):
    return (isinstance(error, urllib.error.HTTPError)
            and 400 <= error.code < 500
            and error.code not in (401, 403, 404, 405, 408, 429))


def endpoint_origin():
    value = private_file(APP_DIR / "smoke.origin")
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("ingestion origin must be an HTTPS origin")
    return value.rstrip("/")


def ingestion_secret():
    lines = private_file(APP_DIR / ".env").splitlines()
    values = [line.split("=", 1)[1] for line in lines
              if line.startswith("HOMEOPS_INGESTION_SHARED_SECRET=")]
    if len(values) != 1 or not re.fullmatch(r"[0-9a-f]{64}", values[0]):
        raise ValueError("ingestion secret must be one 64-character lowercase hexadecimal value")
    return values[0]


def validate_payload(kind, body):
    if kind not in ("deployments", "backups", "signals"):
        raise ValueError("unsupported ingestion event kind")
    if not body or len(body) > MAX_PAYLOAD_BYTES:
        raise ValueError("ingestion payload size is invalid")
    value = json.loads(body)
    if not isinstance(value, dict) or not isinstance(value.get("eventKey"), str):
        raise ValueError("ingestion payload must contain an eventKey")
    if kind == "signals":
        validate_signal_payload(value)
    return value


def validate_signal_payload(value):
    common = {"eventKey", "episodeKey", "project", "signalType", "status", "observedAt"}
    disk = {"availablePercent", "thresholdPercent"}
    http = {"count", "windowSeconds", "thresholdCount"}
    signal_type = value.get("signalType")
    expected = common | (disk if signal_type == "DISK_LOW" else http)
    if signal_type not in ("DISK_LOW", "HTTP_5XX_BURST") or set(value) != expected:
        raise ValueError("signal ingestion fields are invalid")
    if value.get("status") not in ("ALERT", "RECOVERED"):
        raise ValueError("signal ingestion status is invalid")
    for name in ("eventKey", "episodeKey", "project"):
        field = value.get(name)
        if not isinstance(field, str) or not field.strip() or len(field) > 128 or "\0" in field:
            raise ValueError("signal ingestion identity is invalid")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", value["eventKey"]) \
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", value["episodeKey"]) \
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value["project"]):
        raise ValueError("signal ingestion identity is invalid")
    observed_at = value.get("observedAt")
    if not isinstance(observed_at, str) or len(observed_at) > 64:
        raise ValueError("signal ingestion timestamp is invalid")
    try:
        parsed_time = datetime.datetime.fromisoformat(observed_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("signal ingestion timestamp is invalid") from error
    if parsed_time.tzinfo is None:
        raise ValueError("signal ingestion timestamp is invalid")
    if signal_type == "DISK_LOW":
        available = finite_number(value.get("availablePercent"))
        threshold = finite_number(value.get("thresholdPercent"))
        if not 0 <= available <= 100 or not 0 < threshold <= 100:
            raise ValueError("disk signal measurement is invalid")
        return
    if not bounded_integer(value.get("count"), 0, 1_000_000):
        raise ValueError("HTTP signal count is invalid")
    if not bounded_integer(value.get("windowSeconds"), 1, 86_400):
        raise ValueError("HTTP signal window is invalid")
    if not bounded_integer(value.get("thresholdCount"), 1, 1_000_000):
        raise ValueError("HTTP signal threshold is invalid")


def finite_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("signal numeric measurement is invalid")
    number = decimal.Decimal(str(value))
    if not number.is_finite() or number.as_tuple().exponent < -2:
        raise ValueError("signal numeric measurement is invalid")
    return number


def bounded_integer(value, minimum, maximum):
    return not isinstance(value, bool) and isinstance(value, int) and minimum <= value <= maximum


def spool_entry_count():
    root_entries = sum(
        1 for path in SPOOL_DIR.iterdir()
        if path.name not in (".drain.lock", ".writer.lock", "quarantine"))
    quarantine = SPOOL_DIR / "quarantine"
    try:
        quarantine.lstat()
    except FileNotFoundError:
        return root_entries
    private_directory(quarantine)
    return root_entries + sum(1 for _ in quarantine.iterdir())


def write_spool(kind, body):
    SPOOL_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    private_directory(SPOOL_DIR)
    writer_lock_path = SPOOL_DIR / ".writer.lock"
    descriptor = os.open(writer_lock_path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "r+") as writer_lock:
        lock_details = os.fstat(writer_lock.fileno())
        if lock_details.st_uid != os.getuid() or stat.S_IMODE(lock_details.st_mode) != 0o600:
            raise ValueError("ingestion spool writer lock owner or mode is invalid")
        fcntl.flock(writer_lock, fcntl.LOCK_EX)
        if spool_entry_count() >= MAX_SPOOL_ENTRIES:
            raise SpoolCapacityError("ingestion spool capacity is exhausted")
        timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        target = SPOOL_DIR / f"{timestamp}-{uuid.uuid4().hex}.json"
        pending = SPOOL_DIR / f".{timestamp}-{uuid.uuid4().hex}.pending"
        wrapper = json.dumps({"kind": kind, "body": body.decode("utf-8")}, separators=(",", ":"))
        try:
            descriptor = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                output.write(wrapper)
                output.flush()
                os.fsync(output.fileno())
            os.replace(pending, target)
        except Exception:
            if pending.exists() and not pending.is_symlink():
                pending.unlink()
            raise
        return target


def send(origin, secret, kind, body):
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    message = timestamp.encode("utf-8") + b"." + body
    signature = hmac.new(secret.encode("utf-8"), message, hashlib.sha256).hexdigest()
    request = urllib.request.Request(
        f"{origin}/api/v1/internal/ingestion/{kind}",
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-HomeOps-Ingestion-Timestamp": timestamp,
            "X-HomeOps-Ingestion-Signature": signature,
        },
        method="POST",
    )
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
        if not 200 <= response.status < 300:
            raise urllib.error.HTTPError(request.full_url, response.status, "unexpected response", response.headers, None)


def validate_spool_entry(path):
    details = path.lstat()
    if not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid() \
            or stat.S_IMODE(details.st_mode) != 0o600:
        raise ValueError("ingestion spool entry is unsafe")


def drain():
    origin = endpoint_origin()
    secret = ingestion_secret()
    private_directory(SPOOL_DIR)
    lock_path = SPOOL_DIR / ".drain.lock"
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "r+") as lock_file:
        lock_details = os.fstat(lock_file.fileno())
        if lock_details.st_uid != os.getuid() or stat.S_IMODE(lock_details.st_mode) != 0o600:
            raise ValueError("ingestion spool lock owner or mode is invalid")
        fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for path in sorted(SPOOL_DIR.glob("*.json"))[:MAX_DRAIN_FILES]:
            try:
                validate_spool_entry(path)
                wrapper = json.loads(path.read_text(encoding="utf-8"))
                if not isinstance(wrapper, dict):
                    raise ValueError("ingestion spool wrapper must be an object")
                kind = wrapper.get("kind")
                body_text = wrapper.get("body")
                if not isinstance(kind, str) or not isinstance(body_text, str):
                    raise ValueError("ingestion spool wrapper fields are invalid")
                body = body_text.encode("utf-8")
                validate_payload(kind, body)
                send(origin, secret, kind, body)
            except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
                quarantine(path)
            except urllib.error.HTTPError as error:
                if not permanently_rejected(error):
                    raise
                quarantine(path)
            else:
                path.unlink()


def main():
    if len(sys.argv) != 2:
        raise ValueError("usage: report-homeops-event.py <deployments|backups|signal|--drain>")
    argument = sys.argv[1]
    if argument not in ("deployments", "backups", "signal", "--drain"):
        raise ValueError("unsupported ingestion event kind")
    if argument != "--drain":
        kind = "signals" if argument == "signal" else argument
        body = sys.stdin.buffer.read(MAX_PAYLOAD_BYTES + 1)
        validate_payload(kind, body)
        write_spool(kind, body)
    try:
        drain()
    except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError) as exception:
        print(f"HomeOps event retained for retry: {type(exception).__name__}", file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        print(f"HomeOps event reporter rejected input: {type(exception).__name__}", file=sys.stderr)
        raise SystemExit(1)
