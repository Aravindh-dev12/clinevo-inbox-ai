from __future__ import annotations

import re
from .models import Classification, ExtractedValue, SourceRef

PQC_TERMS = {
    "broken seal", "wrong color", "wrong colour", "contamination", "contaminated",
    "damaged packaging", "counterfeit", "cracked", "leaking", "defect", "defective",
}
REACTION_TERMS = {
    "rash", "nausea", "vomiting", "dizziness", "headache", "hospitalized", "hospitalised",
    "anaphylaxis", "reaction", "adverse", "swelling", "fever", "death", "life-threatening",
}
PRODUCT_TERMS = {"drug", "medicine", "tablet", "capsule", "dose", "product", "mg"}
REPORTER_TERMS = {"doctor", "physician", "nurse", "patient", "caregiver", "pharmacist", "reported", "reporter"}
MI_TERMS = {"dose", "dosing", "how to take", "interaction", "can i", "should i", "what is", "how often"}


def _contains_any(text: str, terms: set[str]) -> bool:
    return any(term in text for term in terms)


def classify(text: str) -> list[Classification]:
    normalized = " ".join(text.lower().split())
    labels: list[Classification] = []

    patient_present = bool(re.search(r"\b(patient|male|female|\d{1,3}\s*(?:year|yr)s?\s*old)\b", normalized))
    reporter_present = _contains_any(normalized, REPORTER_TERMS)
    product_present = _contains_any(normalized, PRODUCT_TERMS)
    reaction_present = _contains_any(normalized, REACTION_TERMS)

    if patient_present and reporter_present and product_present and reaction_present:
        labels.append(Classification(
            category="ICSR",
            confidence=0.90,
            reason="Patient, reporter, product and adverse outcome indicators are all present.",
        ))

    pqc_hits = [term for term in PQC_TERMS if term in normalized]
    if pqc_hits:
        labels.append(Classification(
            category="PQC",
            confidence=min(0.98, 0.72 + 0.05 * len(pqc_hits)),
            reason=f"Product-quality terms detected: {', '.join(sorted(pqc_hits)[:4])}.",
        ))

    question_like = "?" in text or _contains_any(normalized, MI_TERMS)
    if question_like and not reaction_present and not pqc_hits:
        labels.append(Classification(
            category="MI",
            confidence=0.86,
            reason="The content asks a product/dosing question without an adverse reaction or product defect.",
        ))

    if not labels:
        labels.append(Classification(
            category="NOT_RELEVANT",
            confidence=0.82,
            reason="No sufficient safety-report, quality-complaint, or medical-information indicators were found.",
        ))

    return labels


def _value(value: str | None, confidence: float, source_name: str, page: int, evidence: str | None = None) -> ExtractedValue:
    if not value:
        return ExtractedValue(value="Not stated", confidence=0.0, source=None)
    return ExtractedValue(
        value=value.strip(),
        confidence=confidence,
        source=SourceRef(source_type="PDF", source_name=source_name, page=page, evidence=evidence),
    )


def extract_safety_facts(text: str, source_name: str, page: int = 1) -> dict[str, dict[str, ExtractedValue]]:
    age = re.search(r"\b(\d{1,3})\s*(?:year|yr)s?\s*old\b", text, re.I)
    sex = re.search(r"\b(male|female)\b", text, re.I)
    dose = re.search(r"\b(\d+(?:\.\d+)?)\s*(mg|mcg|g|ml)\b", text, re.I)
    lot = re.search(r"\b(?:lot|batch)\s*(?:number|no\.?|#)?\s*[:\-]?\s*([A-Z0-9\-]+)\b", text, re.I)

    reaction = next((term for term in REACTION_TERMS if term in text.lower()), None)
    pqc = next((term for term in PQC_TERMS if term in text.lower()), None)

    return {
        "patient": {
            "age": _value(age.group(1) if age else None, 0.95, source_name, page, age.group(0) if age else None),
            "sex": _value(sex.group(1).lower() if sex else None, 0.92, source_name, page, sex.group(0) if sex else None),
            "weight_height": _value(None, 0.0, source_name, page),
            "relevant_history": _value(None, 0.0, source_name, page),
        },
        "reporter": {
            "identity_role": _value(next((r for r in REPORTER_TERMS if r in text.lower()), None), 0.65, source_name, page),
            "country": _value(None, 0.0, source_name, page),
        },
        "product": {
            "name": _value(None, 0.0, source_name, page),
            "dose": _value(" ".join(dose.groups()) if dose else None, 0.94, source_name, page, dose.group(0) if dose else None),
            "route": _value(None, 0.0, source_name, page),
            "start_stop_dates": _value(None, 0.0, source_name, page),
            "batch_lot": _value(lot.group(1) if lot else None, 0.90, source_name, page, lot.group(0) if lot else None),
        },
        "reaction": {
            "event": _value(reaction, 0.72 if reaction else 0.0, source_name, page),
            "onset": _value(None, 0.0, source_name, page),
            "outcome": _value(None, 0.0, source_name, page),
        },
        "severity": {
            "seriousness": _value(
                next((term for term in ["death", "hospitalized", "hospitalised", "life-threatening"] if term in text.lower()), None),
                0.90,
                source_name,
                page,
            )
        },
        "quality_complaint": {
            "issue": _value(pqc, 0.80 if pqc else 0.0, source_name, page),
            "photo_mentioned": _value("yes" if "photo" in text.lower() or "image" in text.lower() else None, 0.75, source_name, page),
        },
    }
