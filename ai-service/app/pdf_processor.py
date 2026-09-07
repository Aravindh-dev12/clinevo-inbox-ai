from __future__ import annotations

import io
import re
import time
from statistics import mean

import fitz
import pdfplumber
import pytesseract
from PIL import Image
from pypdf import PdfReader

from .classifier import classify, extract_facts
from .llm import StructuredLlmClient, validate_provenance
from .models import AiDecision, ImageFinding, ProcessingResult, TableData

try:
    from langdetect import DetectorFactory, LangDetectException, detect
    DetectorFactory.seed = 0
except ImportError:
    LangDetectException = Exception
    detect = None


def _digital_pages(data: bytes) -> list[str]:
    reader = PdfReader(io.BytesIO(data))
    return [page.extract_text() or "" for page in reader.pages]


def _ocr_pages(data: bytes) -> tuple[list[str], float]:
    document = fitz.open(stream=data, filetype="pdf")
    pages: list[str] = []
    confidences: list[float] = []
    for page in document:
        pix = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
        image = Image.open(io.BytesIO(pix.tobytes("png")))
        result = pytesseract.image_to_data(image, output_type=pytesseract.Output.DICT)
        words = [word for word in result.get("text", []) if word and word.strip()]
        pages.append(" ".join(words))
        for raw in result.get("conf", []):
            try:
                value = float(raw)
                if value >= 0:
                    confidences.append(value / 100.0)
            except (TypeError, ValueError):
                pass
    return pages, (mean(confidences) if confidences else 0.0)


def _detect_language(text: str) -> str:
    sample = " ".join(text.split())[:5000]
    if len(sample) < 30:
        return "unknown"
    if detect is not None:
        try:
            return detect(sample)
        except LangDetectException:
            pass
    folded = sample.casefold()
    if any(token in folded for token in (" paciente ", " reacción", " medicamento", "médico")):
        return "es"
    if any(token in folded for token in (" demande", " médicament", " aucun ", "pharmacien")):
        return "fr"
    return "en"


def _article_like(text: str) -> bool:
    lowered = text.casefold()
    return "references" in lowered and any(marker in lowered for marker in ("abstract", "case report", "case 1", "case study"))


def _strip_references(pages: list[str]) -> list[str]:
    cleaned: list[str] = []
    for page in pages:
        match = re.search(r"\bReferences\b", page, re.I)
        cleaned.append(page[:match.start()] if match else page)
    return cleaned


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
        "The result is intended for human review and is not a final pharmacovigilance decision.",
        "Missing facts are left as Not stated rather than inferred.",
    ])
    while len(selected) < 10:
        selected.append("The reviewer should verify extracted values against the cited source page and evidence before acceptance.")
    return " ".join(selected[:10])


def _source_for_llm(file_name: str, pages: list[str], email_text: str) -> str:
    chunks = [f"[EMAIL]\n{email_text.strip()}"] if email_text.strip() else []
    chunks.extend(f"[PDF:{file_name} PAGE:{index}]\n{text}" for index, text in enumerate(pages, start=1))
    return "\n\n".join(chunks)


def process_pdf(file_name: str, data: bytes, email_text: str = "") -> ProcessingResult:
    started = time.perf_counter()
    pages = _digital_pages(data)
    text = "\n\n".join(pages)
    compact_len = len("".join(text.split()))
    ocr_confidence: float | None = None

    if compact_len < 80:
        try:
            pages, ocr_confidence = _ocr_pages(data)
        except Exception:
            pages = pages or [""]
            ocr_confidence = 0.0
        text = "\n\n".join(pages)
        pdf_type = "SCANNED_OR_HANDWRITTEN"
    else:
        language = _detect_language(text)
        if language not in {"en", "unknown"}:
            pdf_type = "NON_ENGLISH"
        elif _article_like(text):
            pdf_type = "PUBLISHED_ARTICLE"
        else:
            pdf_type = "DIGITAL"

    language = _detect_language(text)
    analysis_pages = _strip_references(pages) if pdf_type == "PUBLISHED_ARTICLE" else pages
    analysis_text = "\n\n".join(analysis_pages)
    combined = f"{email_text}\n{analysis_text}".strip()

    fallback_classifications = classify(combined)
    decision = AiDecision(
        classifications=fallback_classifications,
        summary=_summary(analysis_text, fallback_classifications),
        extracted_facts=extract_facts(analysis_pages, file_name),
    )

    llm = StructuredLlmClient()
    llm_decision = llm.decide(_source_for_llm(file_name, analysis_pages, email_text))
    if llm_decision is not None:
        decision = validate_provenance(llm_decision, file_name=file_name, pages=analysis_pages, email_text=email_text)

    processing_ms = int((time.perf_counter() - started) * 1000)
    return ProcessingResult(
        file_name=file_name,
        pdf_type=pdf_type,
        detected_language=language,
        ocr_confidence=ocr_confidence,
        classifications=decision.classifications,
        summary=decision.summary,
        tables=_extract_tables(data),
        images=_extract_images(data),
        extracted_facts=decision.extracted_facts,
        processing_ms=processing_ms,
    )
