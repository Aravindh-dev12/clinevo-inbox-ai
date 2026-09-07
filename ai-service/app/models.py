from __future__ import annotations

from typing import Literal
from pydantic import BaseModel, Field

Category = Literal["ICSR", "PQC", "MI", "NOT_RELEVANT"]
PdfType = Literal["DIGITAL", "SCANNED_OR_HANDWRITTEN", "PUBLISHED_ARTICLE", "NON_ENGLISH"]


class SourceRef(BaseModel):
    source_type: Literal["EMAIL", "PDF"]
    source_name: str
    page: int | None = None
    evidence: str | None = None


class ExtractedValue(BaseModel):
    value: str = "Not stated"
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    source: SourceRef | None = None


class Classification(BaseModel):
    category: Category
    confidence: float = Field(ge=0.0, le=1.0)
    reason: str


class TableData(BaseModel):
    page: int
    rows: list[list[str | None]]


class ImageFinding(BaseModel):
    page: int
    description: str
    requires_human_review: bool = True


class AiDecision(BaseModel):
    classifications: list[Classification]
    summary: str
    extracted_facts: dict[str, dict[str, ExtractedValue]] = Field(default_factory=dict)


class ProcessingResult(BaseModel):
    file_name: str
    pdf_type: PdfType
    detected_language: str
    ocr_confidence: float | None = None
    classifications: list[Classification]
    summary: str
    tables: list[TableData] = Field(default_factory=list)
    images: list[ImageFinding] = Field(default_factory=list)
    extracted_facts: dict[str, dict[str, ExtractedValue]] = Field(default_factory=dict)
    processing_ms: int
