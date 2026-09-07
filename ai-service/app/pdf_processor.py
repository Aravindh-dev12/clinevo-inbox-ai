from __future__ import annotations

import io
import re
import time
from statistics import mean

import fitz
import pdfplumber
import pytesseract
from langdetect import DetectorFactory, LangDetectException, detect
from PIL import Image
from pypdf import PdfReader

from .classifier import classify, extract_safety_facts
from .models import ImageFinding, ProcessingResult, TableData

DetectorFactory.seed = 0


def _digital_text(data: bytes) -> tuple[str, list[str]]:
    reader = PdfReader(io.BytesIO(data))
    pages: list[str] = []
    for page in reader.pages:
        pages.append(page.extract_text() or "")
    return "\n\n".join(pages), pages


def _ocr_text(data: bytes) -> tuple[str, float]:
    document = fitz.open(stream=data, filetype="pdf")
    chunks: list[str] = []
    confidences: list[float] = []
    for page in document:
        pix = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
        image = Image.open(io.BytesIO(pix.tobytes("png")))
        result = pytesseract.image_to_data(image, output_type=pytesseract.Output.DICT)
        words = [word for word in result.get("text", []) if word and word.strip()]
        chunks.append(" ".join(words))
        for raw in result.get("conf", []):
            try:
                value = float(raw)
                if value >= 0:
                    confidences.append(value / 100.0)
            except (TypeError, ValueError):
                pass
    return "\n\n".join(chunks), (mean(confidences) if confidences else 0.0)


def _detect_language(text: str) -> str:
    sample = " ".join(text.split())[:5000]
    if len(sample) < 30:
        return "unknown"
    try:
        return detect(sample)
    except LangDetectException:
        return "unknown"


def _extract_tables(data: bytes) -> list[TableData]:
    output: list[TableData] = []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for page_number, page in enumerate(pdf.pages, start=1):
            for table in page.extract_tables() or []:
                rows = [[cell.strip() if isinstance(cell, str) else cell for cell in row] for row in table]
                if rows:
                    output.append(TableData(page=page_number, rows=rows))
    return output


def _extract_images(data: bytes) -> list[ImageFinding]:
    document = fitz.open(stream=data, filetype="pdf")
    findings: list[ImageFinding] = []
    for page_number, page in enumerate(document, start=1):
        for index, _image in enumerate(page.get_images(full=True), start=1):
            findings.append(ImageFinding(
                page=page_number,
                description=f"Embedded image {index} detected on page {page_number}; verify whether it shows a product issue, reaction, or completed form.",
                requires_human_review=True,
            ))
    return findings


def _summary(text: str, classifications: list) -> str:
    cleaned = " ".join(text.split())
    sentence_candidates = [s.strip() for s in re.split(r"(?<=[.!?])\s+", cleaned) if len(s.strip()) > 20]
    selected = sentence_candidates[:7]
    labels = ", ".join(item.category for item in classifications)
    selected.extend([
        f"The automated first-pass classification is {labels}.",
        "The result is intended for human review and must not be treated as a final safety decision.",
        "Missing facts are left as Not stated rather than inferred.",
    ])
    while len(selected) < 10:
        selected.append("The source should be reviewed against the extracted fields and page-level provenance before acceptance.")
    return " ".join(selected[:10])


def process_pdf(file_name: str, data: bytes, email_text: str = "") -> ProcessingResult:
    started = time.perf_counter()
    text, pages = _digital_text(data)
    compact_len = len("".join(text.split()))
    ocr_confidence: float | None = None

    if compact_len < 80:
        try:
            text, ocr_confidence = _ocr_text(data)
        except Exception:
            # Local Tesseract is optional in development. Keep uncertainty explicit.
            text = text or ""
            ocr_confidence = 0.0
        pdf_type = "SCANNED_OR_HANDWRITTEN"
    else:
        language = _detect_language(text)
        lowered = text.lower()
        if language not in {"en", "unknown"}:
            pdf_type = "NON_ENGLISH"
        elif "abstract" in lowered and "references" in lowered:
            pdf_type = "PUBLISHED_ARTICLE"
        else:
            pdf_type = "DIGITAL"

    language = _detect_language(text)
    combined = f"{email_text}\n{text}".strip()
    classifications = classify(combined)
    facts = extract_safety_facts(text, file_name, page=1)
    tables = _extract_tables(data)
    images = _extract_images(data)

    processing_ms = int((time.perf_counter() - started) * 1000)
    return ProcessingResult(
        file_name=file_name,
        pdf_type=pdf_type,
        detected_language=language,
        ocr_confidence=ocr_confidence,
        classifications=classifications,
        summary=_summary(text, classifications),
        tables=tables,
        images=images,
        extracted_facts=facts,
        processing_ms=processing_ms,
    )
