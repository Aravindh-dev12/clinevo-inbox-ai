from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    response = client.get('/health')
    assert response.status_code == 200
    assert response.json()['status'] == 'ok'


def test_non_pdf_is_rejected():
    response = client.post('/process', files={'file': ('notes.txt', b'hello', 'text/plain')}, data={'email_text': ''})
    assert response.status_code == 415


def test_pdf_size_limit(monkeypatch):
    monkeypatch.setenv('MAX_PDF_BYTES', '8')
    response = client.post('/process', files={'file': ('large.pdf', b'%PDF-123456789', 'application/pdf')}, data={'email_text': ''})
    assert response.status_code == 413
