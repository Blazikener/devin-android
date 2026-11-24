import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import mock_devin_api as mock


class TestHandler(mock.MockDevinHandler):
    def schedule_reply(self, session, prompt):
        pass

    def log_message(self, fmt, *args):
        pass


class AttachmentTest(unittest.TestCase):
    def setUp(self):
        for state in (mock.SESSIONS, mock.MESSAGES, mock.UPLOADS, mock.ATTACHMENTS):
            self.enterContext(patch.dict(state, clear=True))
        server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        self.origin = f"http://127.0.0.1:{server.server_port}"
        self.org = self.origin + "/v3/organizations/org-mock"

    def request(self, url, body=None, content_type="application/json"):
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        req = Request(url, data=body, headers={
            "Authorization": "Bearer cog_mock",
            "Content-Type": content_type,
        })
        with urlopen(req, timeout=5) as response:
            content = response.read()
            return json.loads(content) if response.headers.get_content_type() == "application/json" else content

    def upload(self, name, content):
        boundary = "mock-boundary"
        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
            "Content-Type: text/plain\r\n\r\n"
        ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
        return self.request(self.org + "/attachments", body, f"multipart/form-data; boundary={boundary}")

    def test_create_and_reply_associate_downloadable_uploads(self):
        report_name = "résumé draft #1?.txt"
        first = self.upload(report_name, b"original report")
        second = self.upload("next% draft.txt", b"follow-up")
        self.assertEqual(first["name"], report_name)
        self.assertTrue(first["url"].endswith("/r%C3%A9sum%C3%A9%20draft%20%231%3F.txt"))
        self.assertEqual(self.request(first["url"]), b"original report")

        session = self.request(self.org + "/sessions", {"prompt": "Review this", "attachment_urls": [first["url"]]})
        url = self.org + "/sessions/" + session["session_id"]
        self.assertEqual(
            self.request(url + "/attachments"),
            [dict(first, source="user", content_type="text/plain")],
        )
        self.request(url + "/messages", {"message": "Follow-up", "attachment_urls": [second["url"]]})
        attachments = self.request(url + "/attachments")
        self.assertEqual(attachments, [dict(a, source="user", content_type="text/plain") for a in (first, second)])
        self.assertEqual(self.request(attachments[1]["url"]), b"follow-up")

    def test_invalid_attachment_does_not_create_or_send(self):
        invalid = [self.origin + "/files/not-an-upload.txt"]
        with self.assertRaises(HTTPError) as create:
            self.request(self.org + "/sessions", {"prompt": "Invalid", "attachment_urls": invalid})
        self.assertEqual(create.exception.code, 400)
        self.assertEqual(mock.SESSIONS, {})

        session = self.request(self.org + "/sessions", {"prompt": "Valid", "attachment_urls": None})
        url = self.org + "/sessions/" + session["session_id"]
        with self.assertRaises(HTTPError) as reply:
            self.request(url + "/messages", {"message": "Invalid", "attachment_urls": invalid})
        self.assertEqual(reply.exception.code, 400)
        self.assertEqual([m["message"] for m in self.request(url + "/messages")["items"]], ["Valid"])
        self.assertEqual(self.request(url + "/attachments"), [])


if __name__ == "__main__":
    unittest.main()
