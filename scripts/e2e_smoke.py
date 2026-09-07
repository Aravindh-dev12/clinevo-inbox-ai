#!/usr/bin/env python3
"""End-to-end smoke test for the local Docker Compose stack.

Uses only the Python standard library and synthetic data.
"""

from __future__ import annotations

import json
import os
import smtplib
import socket
import time
import urllib.request
import uuid
from email.message import EmailMessage

BACKEND = os.getenv("SMOKE_BACKEND_URL", "http://127.0.0.1:8080")
AI = os.getenv("SMOKE_AI_URL", "http://127.0.0.1:8000")
FRONTEND = os.getenv("SMOKE_FRONTEND_URL", "http://127.0.0.1:4200")
SMTP_HOST = os.getenv("SMOKE_SMTP_HOST", "127.0.0.1")
SMTP_PORT = int(os.getenv("SMOKE_SMTP_PORT", "3025"))
CLAMAV_HOST = os.getenv("SMOKE_CLAMAV_HOST", "127.0.0.1")
CLAMAV_PORT = int(os.getenv("SMOKE_CLAMAV_PORT", "3310"))
API_KEY = os.getenv("SMOKE_API_KEY", "smoke-secret")


def _request(method: str, url: str, *, body: bytes | None = None,
             headers: dict[str, str] | None = None, timeout: float = 10.0):
    request = urllib.request.Request(url, data=body, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = response.read()
        return response.status, response.headers, payload


def _json(method: str, url: str, *, payload=None, headers: dict[str, str] | None = None):
    merged = {"Accept": "application/json"}
    if headers:
        merged.update(headers)
    body = None
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        merged["Content-Type"] = "application/json"
    status, _, raw = _request(method, url, body=body, headers=merged)
    if status >= 400:
        raise AssertionError(f"{method} {url} returned HTTP {status}")
    return json.loads(raw.decode("utf-8")) if raw else None


def wait_for(name: str, operation, predicate=lambda value: bool(value),
             timeout: float = 240.0, interval: float = 2.0):
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            value = operation()
            if predicate(value):
                print(f"[ok] {name}")
                return value
        except Exception as exc:
            last_error = exc
        time.sleep(interval)
    suffix = f": {last_error}" if last_error else ""
    raise TimeoutError(f"Timed out waiting for {name}{suffix}")


def wait_http():
    wait_for(
        "AI health",
        lambda: _json("GET", f"{AI}/health"),
        lambda value: value.get("status") == "ok",
        timeout=300.0,
    )
    wait_for(
        "backend health",
        lambda: _json("GET", f"{BACKEND}/actuator/health"),
        lambda value: value.get("status") == "UP",
        timeout=420.0,
    )
    wait_for(
        "backend API health",
        lambda: _json("GET", f"{BACKEND}/api/health"),
        lambda value: value.get("status") == "ok",
        timeout=60.0,
    )

    def frontend_ready():
        status, _, body = _request("GET", f"{FRONTEND}/", timeout=5)
        return status == 200 and b"<app-root" in body

    wait_for("frontend", frontend_ready, timeout=120.0)


def wait_clamav():
    def ping():
        with socket.create_connection((CLAMAV_HOST, CLAMAV_PORT), timeout=3) as sock:
            sock.sendall(b"zPING\0")
            return b"PONG" in sock.recv(64)

    wait_for("ClamAV daemon", ping, timeout=300.0)


def make_pdf(text: str) -> bytes:
    escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream = f"BT\n/F1 11 Tf\n72 720 Td\n({escaped}) Tj\nET\n".encode("latin-1")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"endstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, obj in enumerate(objects, start=1):
        offsets.append(len(output))
        output.extend(f"{number} 0 obj\n".encode("ascii"))
        output.extend(obj)
        output.extend(b"\nendobj\n")
    xref = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode("ascii")
    )
    return bytes(output)


def send_synthetic_mail(subject: str, pdf: bytes):
    message = EmailMessage()
    message["From"] = "synthetic.reporter@example.test"
    message["To"] = "ci@localhost"
    message["Subject"] = subject
    message["Message-ID"] = f"<{uuid.uuid4()}@clinevo-smoke.test>"
    message.set_content(
        "Synthetic safety report. Patient is a 44-year-old female. "
        "Reporter: Dr Synthetic, pharmacist. Drug: Trialmed 10 mg oral. "
        "The patient developed a severe rash and is recovering."
    )
    message.add_attachment(pdf, maintype="application", subtype="pdf", filename="synthetic-icsr.pdf")
    with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=10) as client:
        client.send_message(message)
    print("[ok] synthetic email delivered through SMTP")


def wait_for_review_item(subject: str):
    def find_item():
        items = _json("GET", f"{BACKEND}/api/inbox")
        for item in items:
            if item.get("subject") == subject:
                return item
        return None

    item = wait_for("mailbox ingestion", find_item, timeout=180.0)
    message_id = item["id"]

    def ready():
        detail = _json("GET", f"{BACKEND}/api/inbox/{message_id}/detail")
        if detail["message"].get("status") == "PROCESSING_FAILED":
            raise AssertionError(f"processing failed: {detail}")
        return detail if detail["message"].get("status") == "READY_FOR_REVIEW" else None

    detail = wait_for("durable queue and AI processing", ready, timeout=240.0)
    return message_id, detail


def assert_processed(detail: dict):
    categories = {entry["category"] for entry in detail["classifications"]}
    assert "ICSR" in categories, f"expected ICSR classification, got {categories}"
    jobs = detail.get("processingJobs", [])
    assert any(job.get("status") == "SUCCEEDED" for job in jobs), jobs

    attachments = detail["attachments"]
    assert attachments, "expected staged attachment"
    pdf = next((entry for entry in attachments if entry["fileName"] == "synthetic-icsr.pdf"), None)
    assert pdf is not None, attachments
    assert pdf["processingStatus"] == "PROCESSED", pdf
    assert pdf["malwareScanStatus"] == "CLEAN", pdf
    assert pdf["storageProvider"] == "FILESYSTEM", pdf
    assert isinstance(pdf["sha256"], str) and len(pdf["sha256"]) == 64, pdf

    pdf_facts = [fact for fact in detail["facts"] if fact.get("sourceType") == "PDF"]
    assert pdf_facts, "expected facts sourced from the PDF"
    assert any(fact.get("sourcePage") == 1 and fact.get("evidenceText") for fact in pdf_facts), pdf_facts
    print("[ok] SUCCEEDED job, CLEAN scan, immutable storage, ICSR classification and page provenance")


def review(message_id: int):
    payload = {
        "action": "ACCEPT",
        "note": "automated end-to-end smoke acceptance",
        "reviewer": "ci-reviewer",
        "factOverrides": [],
    }
    result = _json(
        "POST",
        f"{BACKEND}/api/inbox/{message_id}/review",
        payload=payload,
        headers={"X-API-Key": API_KEY},
    )
    assert result["status"] == "REVIEW_ACCEPTED", result
    detail = _json("GET", f"{BACKEND}/api/inbox/{message_id}/detail")
    assert any(
        action["actionType"] == "ACCEPT" and action["reviewer"] == "ci-reviewer"
        for action in detail["reviewActions"]
    ), detail["reviewActions"]
    print("[ok] protected reviewer ACCEPT persisted to audit history")


def multipart_literature(pdf: bytes):
    boundary = f"clinevo-smoke-{uuid.uuid4().hex}"
    head = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="files"; filename="synthetic-literature.pdf"\r\n'
        "Content-Type: application/pdf\r\n\r\n"
    ).encode("ascii")
    body = head + pdf + f"\r\n--{boundary}--\r\n".encode("ascii")
    status, _, raw = _request(
        "POST",
        f"{BACKEND}/api/literature/screen",
        body=body,
        headers={
            "Accept": "application/json",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-API-Key": API_KEY,
        },
        timeout=60,
    )
    assert status == 200, status
    result = json.loads(raw.decode("utf-8"))
    assert result.get("total_documents") == 1, result
    assert len(result.get("documents", [])) == 1, result
    document = result["documents"][0]
    assert document.get("file_name") == "synthetic-literature.pdf", result
    assert isinstance(document.get("cases"), list), result
    print("[ok] literature batch endpoint")


def main():
    wait_http()
    wait_clamav()
    text = (
        "Patient: 44-year-old female. Reporter: pharmacist. Country: Canada. "
        "Drug: Trialmed 10 mg oral. Start date: 01-Jan-2026. "
        "Adverse event: severe rash. Outcome: recovering. No death or hospitalization."
    )
    pdf = make_pdf(text)
    subject = f"Clinevo synthetic E2E {uuid.uuid4().hex[:10]}"
    send_synthetic_mail(subject, pdf)
    message_id, detail = wait_for_review_item(subject)
    assert_processed(detail)
    review(message_id)
    multipart_literature(pdf)
    print("[success] Clinevo Docker Compose end-to-end smoke passed")


if __name__ == "__main__":
    main()
