from fastapi.testclient import TestClient

from app.main import app
from app.text_processor import process_text


def test_email_only_safety_case_has_email_provenance():
    text = "A 45-year-old female patient was reported by a physician after Drug Delta 10 mg and developed rash."
    result = process_text("email:123", text)
    assert any(item.category == "ICSR" for item in result.classifications)
    age = result.extracted_facts["patient"]["age"]
    assert age.value == "45"
    assert age.source is not None
    assert age.source.source_type == "EMAIL"
    assert age.source.source_name == "email:123"
    assert age.source.page is None


def test_process_text_api_supports_messages_without_pdf():
    response = TestClient(app).post("/process-text", json={
        "source_name": "email:456",
        "text": "Can I take Drug Omega 5 mg twice daily?",
    })
    assert response.status_code == 200
    assert any(item["category"] == "MI" for item in response.json()["classifications"])


def test_pdf_magic_signature_is_required():
    response = TestClient(app).post(
        "/process",
        files={"file": ("fake.pdf", b"this is not a pdf", "application/pdf")},
        data={"email_text": "synthetic"},
    )
    assert response.status_code == 415
