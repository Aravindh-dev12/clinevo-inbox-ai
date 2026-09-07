import os

from fastapi import FastAPI, File, Form, HTTPException, UploadFile

from .models import ProcessingResult
from .pdf_processor import process_pdf

app = FastAPI(title="Clinevo Inbox AI Service", version="0.2.0", description="Synthetic-data-only document understanding service for the candidate assignment.")


def _max_pdf_bytes() -> int:
    return int(os.getenv("MAX_PDF_BYTES", str(20 * 1024 * 1024)))


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "clinevo-ai-service"}


@app.post("/process", response_model=ProcessingResult)
async def process_document(file: UploadFile = File(...), email_text: str = Form(default="")) -> ProcessingResult:
    content_type = (file.content_type or "").lower()
    if content_type != "application/pdf" and not (file.filename or "").lower().endswith(".pdf"):
        raise HTTPException(status_code=415, detail="Only PDF attachments are processed; other file types should be logged by the Java service.")
    limit = _max_pdf_bytes()
    data = await file.read(limit + 1)
    if not data:
        raise HTTPException(status_code=400, detail="Empty PDF")
    if len(data) > limit:
        raise HTTPException(status_code=413, detail="PDF exceeds the configured processing limit")
    try:
        return process_pdf(file.filename or "attachment.pdf", data, email_text=email_text)
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Document processing failed") from exc
