#!/usr/bin/env python3
"""A small, stateful mock of the Devin v3 API for local Android development."""

import argparse
import copy
import json
import re
import threading
import time
import uuid
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


LOCK = threading.RLock()
SESSIONS = {}
MESSAGES = {}
UPLOADS = {}
ATTACHMENTS = {}
NOW = int(time.time())


def session_id(hex_id):
    return f"devin-{hex_id}"


def make_session(
    hex_id,
    title,
    status="running",
    status_detail=None,
    age=0,
    archived=False,
    parent_session_id=None,
    pull_requests=None,
):
    session_hex = hex_id or uuid.uuid4().hex
    created = NOW - age
    return {
        "session_id": session_id(session_hex),
        "url": f"https://app.devin.ai/sessions/{session_hex}",
        "status": status,
        "title": title,
        "tags": [],
        "playbook_id": None,
        "user_id": "user-mock",
        "created_at": created,
        "updated_at": created + 30,
        "is_archived": archived,
        "acus_consumed": round((age % 130) / 10.0 + 0.2, 1),
        "pull_requests": pull_requests or [],
        "parent_session_id": parent_session_id,
        "child_session_ids": [],
        "origin": "web",
        "devin_mode": "normal",
        "status_detail": status_detail,
    }


def add_message(session, source, text, age, username=None):
    event = {
        "event_id": f"event-{uuid.uuid4().hex}",
        "source": source,
        "message": text,
        "created_at": NOW - age,
        "username": username,
        "user_id": "user-mock" if source == "user" else None,
        "origin": "web",
    }
    MESSAGES.setdefault(session, []).append(event)


def seed_data():
    samples = [
        make_session("10000000000000000000000000000001", "Add retry handling to the API client", age=120),
        make_session("20000000000000000000000000000002", "Investigate flaky Android tests", status_detail="waiting_for_user", age=240),
        make_session("30000000000000000000000000000003", "Review dependency update", status_detail="waiting_for_approval", age=360),
        make_session("40000000000000000000000000000004", "Update the release notes", status="suspended", status_detail="inactivity", age=480),
        make_session("50000000000000000000000000000005", "Document the v3 session API", status="exit", status_detail="finished", age=600),
        make_session("60000000000000000000000000000006", "Fix the notification permission crash", status="error", status_detail="error", age=720),
        make_session("70000000000000000000000000000007", "Refactor the caching layer", status_detail="usage_limit_exceeded", age=840),
        make_session(
            "80000000000000000000000000000008",
            "Prepare backend migration pull request",
            archived=True,
            age=960,
            pull_requests=[{"pr_url": "https://github.com/acme/web/pull/42", "pr_state": "open"}],
        ),
        make_session(
            "90000000000000000000000000000009",
            "Add unit tests for paging",
            age=1080,
            parent_session_id=session_id("10000000000000000000000000000001"),
        ),
    ]
    for item in samples:
        SESSIONS[item["session_id"]] = item
    SESSIONS[session_id("10000000000000000000000000000001")]["child_session_ids"] = [
        session_id("90000000000000000000000000000009")
    ]

    add_message(
        samples[0]["session_id"],
        "user",
        "Please add retry handling to the API client. Keep the change small.",
        300,
        "Mock User",
    )
    add_message(
        samples[0]["session_id"],
        "devin",
        "I traced the request flow and found a small place to add a bounded retry. "
        "The client currently has this structure:\n\n"
        "```kotlin\n"
        "suspend fun loadSession(id: String) = api.session(id)\n"
        "```\n\n"
        "I'll retry transient responses only, so authentication errors are not repeated. "
        "The implementation keeps **the existing API** and reports failures with `ApiException`.\n\n"
        "- Retry connection failures\n"
        "- Keep the attempt count bounded\n"
        "- See the [OkHttp retry guidance](https://square.github.io/okhttp/)",
        240,
    )
    add_message(
        samples[0]["session_id"],
        "user",
        "Great, please include a unit test for the retry limit too.",
        180,
        "Mock User",
    )
    add_message(
        samples[1]["session_id"], "user", "The session list test flakes on the second page.", 350, "Mock User"
    )
    add_message(
        samples[1]["session_id"],
        "devin",
        "I can investigate the cursor behavior. Do you have a failing run or expected cursor value to share?",
        250,
    )
    add_message(
        samples[2]["session_id"],
        "devin",
        "The dependency update is ready for review. It changes the serialization plugin and no runtime APIs.",
        330,
    )
    add_message(
        samples[4]["session_id"],
        "devin",
        "The v3 API guide is drafted and includes paging, authentication, and attachment examples.",
        620,
    )
    add_message(
        samples[8]["session_id"], "devin", "I added tests for cursors and unknown response fields.", 1100
    )
    ATTACHMENTS[samples[4]["session_id"]] = [
        {
            "attachment_id": "attachment-mock-report",
            "name": "report.md",
            "url": "/files/report.md",
            "content_type": "text/markdown",
            "source": "devin",
        }
    ]


class MockDevinHandler(BaseHTTPRequestHandler):
    server_version = "MockDevinAPI/1.0"

    def log_message(self, fmt, *args):
        print(
            f'{self.log_date_time_string()} {self.client_address[0]} "{self.requestline}" {fmt % args}',
            flush=True,
        )

    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def do_DELETE(self):
        self.dispatch()

    def respond(self, status, payload, content_type="application/json"):
        if isinstance(payload, (dict, list)):
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        else:
            body = payload if isinstance(payload, bytes) else payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def error(self, status, detail):
        self.respond(status, {"detail": detail})

    def read_body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def json_body(self):
        return json.loads(self.read_body() or b"{}")

    def authorized(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer cog_"):
            return True
        self.error(401, "Invalid API key")
        return False

    def base_url(self):
        return f"http://{self.headers.get('Host', f'127.0.0.1:{self.server.server_port}')}"

    def dispatch(self):
        if not self.authorized():
            return
        parsed = urlsplit(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query)
        parts = path.split("/")

        if path.startswith("/files/"):
            self.do_file(path)
        elif self.command == "GET" and path == "/v3/self":
            self.respond(
                200,
                {
                    "principal_type": "service_user",
                    "org_id": "org-mock",
                    "user_id": "user-mock",
                    "user_name": "Mock User",
                },
            )
        elif len(parts) == 5 and parts[1:3] == ["v3", "organizations"]:
            self.handle_collection(parts[3], parts[4], query)
        elif len(parts) >= 6 and parts[1:3] == ["v3", "organizations"]:
            self.handle_session(parts[3], parts[4], parts[5:], query)
        else:
            self.error(404, "Not found")

    def handle_collection(self, org, collection, query):
        if collection == "sessions":
            if self.command == "GET":
                self.list_sessions(query)
            elif self.command == "POST":
                self.create_session()
            else:
                self.error(404, "Not found")
        elif collection == "attachments" and self.command == "POST":
            self.upload_attachment(org)
        elif collection == "repositories" and self.command == "GET":
            self.list_repositories(query)
        elif collection == "playbooks" and self.command == "GET":
            self.list_playbooks(query)
        else:
            self.error(404, "Not found")

    def page(self, entries, query, cursor_key):
        try:
            first = max(1, min(int(query.get("first", ["50"])[0]), 500))
        except ValueError:
            first = 50
        start = 0
        after = query.get("after", [None])[0]
        if after:
            for index, entry in enumerate(entries):
                if entry[cursor_key] == after:
                    start = index + 1
                    break
        selected = entries[start : start + first]
        has_next = start + len(selected) < len(entries)
        return {
            "items": selected,
            "end_cursor": selected[-1][cursor_key] if selected else None,
            "has_next_page": has_next,
            "total": len(entries),
        }

    def list_sessions(self, query):
        with LOCK:
            entries = list(SESSIONS.values())
            archived = query.get("is_archived", [None])[0]
            if archived is not None:
                want_archived = archived.lower() == "true"
                entries = [entry for entry in entries if entry["is_archived"] == want_archived]
            users = set(query.get("user_ids", []))
            if users:
                entries = [entry for entry in entries if entry.get("user_id") in users]
            session_ids = set(query.get("session_ids", []))
            if session_ids:
                entries = [entry for entry in entries if entry["session_id"] in session_ids]
            updated_after = query.get("updated_after", [None])[0]
            if updated_after:
                try:
                    entries = [entry for entry in entries if entry["updated_at"] > int(updated_after)]
                except ValueError:
                    pass
            entries.sort(key=lambda entry: (entry["updated_at"], entry["session_id"]), reverse=True)
            result = self.page(entries, query, "session_id")
        self.respond(200, result)

    def get_session(self, session):
        with LOCK:
            item = SESSIONS.get(session)
            return copy.deepcopy(item) if item else None

    def handle_session(self, org, collection, suffix, query):
        if collection != "sessions" or not suffix:
            self.error(404, "Not found")
            return
        session = suffix[0]
        if len(suffix) == 1:
            if self.command == "GET":
                item = self.get_session(session)
                self.respond(200, item) if item else self.error(404, "Not found")
            elif self.command == "DELETE":
                with LOCK:
                    item = SESSIONS.get(session)
                    if item:
                        item["status"] = "exit"
                        item["status_detail"] = "finished"
                        item["updated_at"] = int(time.time())
                        item = copy.deepcopy(item)
                self.respond(200, item) if item else self.error(404, "Not found")
            else:
                self.error(404, "Not found")
            return

        action = suffix[1]
        if action == "messages" and len(suffix) == 2:
            if self.command == "GET":
                self.list_messages(session, query)
            elif self.command == "POST":
                self.send_message(session)
            else:
                self.error(404, "Not found")
        elif action == "attachments" and len(suffix) == 2 and self.command == "GET":
            with LOCK:
                result = copy.deepcopy(ATTACHMENTS.get(session, []))
            if session not in SESSIONS:
                self.error(404, "Not found")
            else:
                for attachment in result:
                    if attachment["url"].startswith("/"):
                        attachment["url"] = self.base_url() + attachment["url"]
                self.respond(200, result)
        elif action in ("sleep", "archive", "unarchive") and len(suffix) == 2 and self.command == "POST":
            self.mutate_session(session, action)
        else:
            self.error(404, "Not found")

    def mutate_session(self, session, action):
        with LOCK:
            item = SESSIONS.get(session)
            if item:
                if action == "sleep":
                    item["status"] = "suspended"
                    item["status_detail"] = "user_request"
                elif action == "archive":
                    item["is_archived"] = True
                else:
                    item["is_archived"] = False
                item["updated_at"] = int(time.time())
                item = copy.deepcopy(item)
        self.respond(200, item) if item else self.error(404, "Not found")

    def list_messages(self, session, query):
        with LOCK:
            if session not in SESSIONS:
                self.error(404, "Not found")
                return
            entries = sorted(MESSAGES.get(session, []), key=lambda entry: entry["created_at"])
            self.respond(200, self.page(entries, query, "event_id"))

    def add_user_message(self, session, message, username="Mock User"):
        with LOCK:
            add_message(session, "user", message, 0, username)
            SESSIONS[session]["status"] = "running"
            SESSIONS[session]["status_detail"] = None
            SESSIONS[session]["updated_at"] = int(time.time())

    def schedule_reply(self, session, prompt):
        def reply():
            with LOCK:
                if session not in SESSIONS:
                    return
                add_message(
                    session,
                    "devin",
                    f"I've received your request: **{prompt[:160]}**\n\n"
                    "I'll work through it now. You can send another message whenever you're ready.",
                    0,
                )
                SESSIONS[session]["status"] = "running"
                SESSIONS[session]["status_detail"] = "waiting_for_user"
                SESSIONS[session]["updated_at"] = int(time.time())

        timer = threading.Timer(3.0, reply)
        timer.daemon = True
        timer.start()

    def send_message(self, session):
        with LOCK:
            if session not in SESSIONS:
                self.error(404, "Not found")
                return
        try:
            body = self.json_body()
            message = body["message"]
            if not isinstance(message, str):
                raise ValueError
        except (KeyError, ValueError, json.JSONDecodeError):
            self.error(400, "Invalid message")
            return
        self.add_user_message(session, message)
        self.schedule_reply(session, message)
        self.respond(200, self.get_session(session))

    def create_session(self):
        try:
            body = self.json_body()
            prompt = body["prompt"]
            if not isinstance(prompt, str) or not prompt.strip():
                raise ValueError
        except (KeyError, ValueError, json.JSONDecodeError):
            self.error(400, "Invalid prompt")
            return
        created = int(time.time())
        hex_id = uuid.uuid4().hex
        item = make_session(hex_id, body.get("title") or prompt[:80])
        item.update(
            {
                "created_at": created,
                "updated_at": created,
                "tags": body.get("tags") or [],
                "playbook_id": body.get("playbook_id"),
                "devin_mode": body.get("devin_mode") or "normal",
            }
        )
        with LOCK:
            SESSIONS[item["session_id"]] = item
            add_message(item["session_id"], "user", prompt, 0, "Mock User")
        self.schedule_reply(item["session_id"], prompt)
        self.respond(200, item)

    def upload_attachment(self, org):
        content_type = self.headers.get("Content-Type", "")
        match = re.search(r'boundary=(?:"([^"]+)"|([^;]+))', content_type)
        if not match:
            self.error(400, "Expected multipart file upload")
            return
        boundary = (match.group(1) or match.group(2)).strip()
        raw = self.read_body()
        message = BytesParser(policy=policy.default).parsebytes(
            f"Content-Type: multipart/form-data; boundary={boundary}\r\nMIME-Version: 1.0\r\n\r\n".encode()
            + raw
        )
        file_part = next(
            (
                part
                for part in message.iter_parts()
                if part.get_content_disposition() == "form-data" and part.get_param("name", header="content-disposition") == "file"
            ),
            None,
        )
        if file_part is None:
            self.error(400, "Missing file")
            return
        name = file_part.get_filename() or "upload.bin"
        attachment_id = f"upload-{uuid.uuid4().hex}"
        relative_url = f"/files/{attachment_id}/{name}"
        with LOCK:
            UPLOADS[relative_url] = (file_part.get_content_type(), file_part.get_payload(decode=True) or b"")
        self.respond(
            200,
            {
                "attachment_id": attachment_id,
                "name": name,
                "url": self.base_url() + relative_url,
            },
        )

    def list_repositories(self, query):
        items = [
            {"repo_name": "web", "repo_path": "acme/web", "repo_full_name": "acme/web", "repo_description": "Customer web app", "repo_language": "TypeScript"},
            {"repo_name": "android", "repo_path": "acme/android", "repo_full_name": "acme/android", "repo_description": "Android client", "repo_language": "Kotlin"},
            {"repo_name": "platform", "repo_path": "acme/platform", "repo_full_name": "acme/platform", "repo_description": "Shared platform services", "repo_language": "Go"},
        ]
        filter_name = query.get("filter_name", [""])[0].lower()
        if filter_name:
            items = [item for item in items if filter_name in item["repo_name"].lower() or filter_name in item["repo_full_name"].lower()]
        self.respond(200, self.page(items, query, "repo_full_name"))

    def list_playbooks(self, query):
        items = [
            {"playbook_id": "playbook-bug-fix", "title": "Fix a bug", "macro": "Investigate and fix the reported issue."},
            {"playbook_id": "playbook-code-review", "title": "Code review", "macro": "Review the changes for correctness and risks."},
            {"playbook_id": "playbook-feature", "title": "Implement a feature", "macro": "Plan, implement, and test the requested feature."},
        ]
        self.respond(200, self.page(items, query, "playbook_id"))

    def do_file(self, path):
        if self.command != "GET":
            self.error(404, "Not found")
            return
        if path == "/files/report.md":
            content = (
                "# Mock Devin report\n\n"
                "This sample report is served by the local mock API.\n\n"
                "- Attachments use authenticated downloads\n"
                "- Uploads are accepted as multipart form data\n"
            ).encode("utf-8")
            self.respond(200, content, "text/markdown; charset=utf-8")
            return
        with LOCK:
            upload = UPLOADS.get(path)
        if upload:
            self.respond(200, upload[1], upload[0])
        else:
            self.error(404, "Not found")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    seed_data()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), MockDevinHandler)
    print(f"Mock Devin API listening on http://0.0.0.0:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
