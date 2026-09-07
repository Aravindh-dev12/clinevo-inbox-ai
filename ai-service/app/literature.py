from __future__ import annotations

import re
import time
from dataclasses import dataclass, field

from .classifier import classify, extract_facts
from .models import LiteratureCaseResult, LiteratureDocumentResult
from .pdf_processor import _article_like, _detect_language, _digital_pages, _ocr_pages, _strip_references

_CASE_HEADING = re.compile(r"^\s*(?:case|patient)\s*(?:report\s*)?(?:#\s*)?(\d+)\b[:.\-\s]*(.*)$", re.I)


@dataclass
class _Segment:
    case_id: str
    page_lines: dict[int, list[str]] = field(default_factory=dict)

    def append(self, page: int, line: str) -> None:
        if line.strip():
            self.page_lines.setdefault(page, []).append(line.strip())


def _case_segments(pages: list[str]) -> list[_Segment]:
    segments: list[_Segment] = []
    current: _Segment | None = None
    saw_numbered_case = False

    for page_number, page in enumerate(pages, start=1):
        for line in page.splitlines():
            match = _CASE_HEADING.match(line)
            if match:
                saw_numbered_case = True
                if current is not None and current.page_lines:
                    segments.append(current)
                current = _Segment(case_id=f"case-{match.group(1)}")
                remainder = match.group(2).strip()
                if remainder:
                    current.append(page_number, remainder)
                continue
            if current is not None:
                current.append(page_number, line)

    if current is not None and current.page_lines:
        segments.append(current)

    if saw_numbered_case:
        return segments

    fallback = _Segment(case_id="case-1")
    for page_number, page in enumerate(pages, start=1):
        if page.strip():
            fallback.page_lines[page_number] = [page]
    return [fallback] if fallback.page_lines else []


def _scoped_pages(segment: _Segment, total_pages: int) -> list[str]:
    return ["\n".join(segment.page_lines.get(page_number, [])) for page_number in range(1, total_pages + 1)]


def _summary(text: str, reportable: bool, labels: str) -> str:
    cleaned = " ".join(text.split())
    sentences = [item.strip() for item in re.split(r"(?<=[.!?])\s+", cleaned) if len(item.strip()) > 20]
    selected = sentences[:3]
    if reportable:
        selected.append("This segment meets the assignment's minimum first-pass ICSR screening criteria and should be reviewed by a human safety reviewer.")
    else:
        selected.append("This segment does not currently satisfy all four minimum ICSR elements required for a reportable case candidate.")
    selected.append(f"Automated labels for this segment are {labels or 'none'}; source-page provenance is retained for extracted facts.")
    return " ".join(selected)


def screen_literature_pdf(file_name: str, data: bytes) -> LiteratureDocumentResult:
    started = time.perf_counter()
    pages = _digital_pages(data)
    compact_len = len("".join("".join(pages).split()))
    ocr_used = False

    if compact_len < 80:
        try:
            pages, _confidence = _ocr_pages(data)
            ocr_used = True
        except Exception:
            pages = pages or [""]

    text = "\n\n".join(pages)
    language = _detect_language(text)
    if ocr_used:
        pdf_type = "SCANNED_OR_HANDWRITTEN"
    elif language not in {"en", "unknown"}:
        pdf_type = "NON_ENGLISH"
    elif _article_like(text):
        pdf_type = "PUBLISHED_ARTICLE"
    else:
        pdf_type = "DIGITAL"

    analysis_pages = _strip_references(pages)
    cases: list[LiteratureCaseResult] = []
    for segment in _case_segments(analysis_pages):
        scoped = _scoped_pages(segment, len(analysis_pages))
        case_text = "\n\n".join(scoped).strip()
        if not case_text:
            continue
        classifications = classify(case_text)
        icsr = next((item for item in classifications if item.category == "ICSR"), None)
        reportable = icsr is not None
        confidence = icsr.confidence if icsr is not None else max((item.confidence for item in classifications), default=0.5)
        labels = ", ".join(item.category for item in classifications)
        reason = (
            "Specific patient, reporter, suspect product and non-negated adverse reaction are all supported in this case segment."
            if reportable
            else "The segment does not support all four minimum ICSR elements (patient, reporter, suspect product and adverse reaction)."
        )
        cases.append(LiteratureCaseResult(
            case_id=segment.case_id,
            reportable=reportable,
            confidence=confidence,
            relevance_reason=reason,
            summary=_summary(case_text, reportable, labels),
            classifications=classifications,
            extracted_facts=extract_facts(scoped, file_name) if reportable else {},
            source_pages=sorted(segment.page_lines),
        ))

    return LiteratureDocumentResult(
        file_name=file_name,
        pdf_type=pdf_type,
        detected_language=language,
        cases=cases,
        case_count=len(cases),
        processing_ms=int((time.perf_counter() - started) * 1000),
    )
