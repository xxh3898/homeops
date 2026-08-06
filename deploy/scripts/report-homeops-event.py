#!/usr/bin/python3

import datetime
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
    if kind not in ("deployments", "backups"):
        raise ValueError("unsupported ingestion event kind")
    if not body or len(body) > MAX_PAYLOAD_BYTES:
        raise ValueError("ingestion payload size is invalid")
    value = json.loads(body)
    if not isinstance(value, dict) or not isinstance(value.get("eventKey"), str):
        raise ValueError("ingestion payload must contain an eventKey")
    return value


def write_spool(kind, body):
    SPOOL_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    private_directory(SPOOL_DIR)
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
            if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o600:
                raise ValueError("ingestion spool entry is unsafe")
            try:
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
        raise ValueError("usage: report-homeops-event.py <deployments|backups|--drain>")
    if sys.argv[1] != "--drain":
        body = sys.stdin.buffer.read(MAX_PAYLOAD_BYTES + 1)
        validate_payload(sys.argv[1], body)
        write_spool(sys.argv[1], body)
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
