from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from .models import Classification, ExtractedValue, SourceRef

PQC_TERMS = (
    "broken seal", "seal was broken", "broken foil seal", "wrong color", "wrong colour",
    "unusual color", "unusual colour", "darker than expected", "contamination", "contaminated",
    "damaged packaging", "damaged", "counterfeit", "cracked", "leaking", "leaked", "defect", "defective",
    "would not click", "sello roto", "color incorrecto", "emballage endommagé", "fissuré",
)
PQC_PATTERNS = (
    (
        r"\b(?:tablets?|capsules?|gel|solution|syringe|injector|autoinjector|pen|product|device)\b.{0,100}"
        r"\b(?:blue|white|red|yellow|green|black|brown|clear|cloudy)\b.{0,40}\binstead of\b.{0,40}"
        r"\b(?:usual|expected|normal)\b",
        "unexpected product color",
    ),
    (
        r"\b(?:wrong|unexpected|unusual|different)\s+(?:product\s+)?(?:color|colour)\b",
        "unexpected product color",
    ),
)
REACTION_TERMS = (
    "anaphylaxis", "life-threatening", "hospitalized", "hospitalised", "hospitalization",
    "rash", "nausea", "vomiting", "dizziness", "headache", "swelling", "redness", "itching",
    "diarrhea", "diarrhoea", "syncope", "abdominal pain", "pain", "fever", "death",
    "erupción", "picor", "mareo", "vómitos", "douleur", "gonflement",
)
REPORTER_TERMS = (
    "doctor", "physician", "nurse", "patient", "caregiver", "pharmacist", "clinician", "clinicians",
    "reported", "reporter", "self-reported", "reporting",
    "médico", "medico", "enfermera", "paciente", "farmacéutico", "pharmacien", "médecin",
)
MI_TERMS = (
    "dose", "dosing", "how to take", "interaction", "can i", "should i", "what is", "how often",
    "dose adjustment", "taken with food", "puede", "dosis", "interacción", "interaction", "peut-il", "comment prendre",
)
NEGATION_WORDS = ("no", "not", "without", "denies", "denied", "aucun", "aucune", "sans", "sin", "ningún", "ninguna")
ROUTE_TERMS = ("oral", "orally", "intravenous", "iv", "subcutaneous", "intramuscular", "topical", "por vía oral")
COUNTRIES = (
    "India", "United Kingdom", "UK", "Canada", "Australia", "Ireland", "Singapore", "Spain",
    "New Zealand", "France", "España",
)
_PRODUCT_STOPWORDS = {
    "quality", "complaint", "information", "request", "safety", "report", "case", "device", "adverse",
    "name", "was", "were", "not", "provided", "unknown", "unspecified", "missing", "suspect",
}
_PRODUCT_PATTERNS = (
    r"\b(?:product|drug|medicine|medication|producto|produit)\s*[:\-]?\s*([A-Za-z][A-Za-z0-9\-]{2,})\b",
    r"\b([A-Za-z][A-Za-z0-9\-]{2,})\s+\d+(?:\.\d+)?\s*(?:mg|mcg|g|ml)\b",
    r"\b([A-Za-z][A-Za-z0-9\-]{2,})\s+(?:tablets?|capsules?|gel|syringe|injector|autoinjector|pen)\b",
    r"\b(?:first|next|last)\s+([A-Za-z][A-Za-z0-9\-]{2,})\s+dose\b",
    r"\b(?:started|received|took|taking|used|using)\s+(?:(?:a|an|the)\s+)?([A-Za-z][A-Za-z0-9\-]{2,})\b",
)


@dataclass(frozen=True)
class Located:
    value: str
    page: int
    evidence: str


def _fold(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    return "".join(ch for ch in normalized if not unicodedata.combining(ch)).lower()


def _context(text: str, start: int, end: int, radius: int = 55) -> str:
    left = max(0, start - radius)
    right = min(len(text), end + radius)
    return " ".join(text[left:right].split())


def _is_negated(text: str, start: int) -> bool:
    # Keep negation local to the same sentence/clause, but allow enough tokens for
    # phrases such as "No adverse event or product defect was reported".
    prefix = _fold(text[max(0, start - 100):start])
    boundary = max(prefix.rfind("."), prefix.rfind(";"), prefix.rfind("!"), prefix.rfind("?"), prefix.rfind("\n"))
    if boundary >= 0:
        prefix = prefix[boundary + 1:]
    return bool(re.search(r"\b(?:" + "|".join(re.escape(_fold(x)) for x in NEGATION_WORDS) + r")\b(?:\W+\w+){0,7}\W*$", prefix))


def _nonnegated_terms(text: str, terms: tuple[str, ...]) -> list[str]:
    folded = _fold(text)
    hits: list[str] = []
    for term in terms:
        folded_term = _fold(term)
        for match in re.finditer(re.escape(folded_term), folded):
            if not _is_negated(folded, match.start()):
                hits.append(term)
                break
    return hits


def _nonnegated_quality_patterns(text: str) -> list[str]:
    folded = _fold(text)
    hits: list[str] = []
    for pattern, label in PQC_PATTERNS:
        for match in re.finditer(pattern, folded, re.I):
            if not _is_negated(folded, match.start()):
                hits.append(label)
                break
    return hits


def _product_match(text: str) -> tuple[str, int, int] | None:
    for pattern in _PRODUCT_PATTERNS:
        for match in re.finditer(pattern, text, re.I):
            candidate = match.group(1).strip()
            if _fold(candidate) in _PRODUCT_STOPWORDS:
                continue
            return candidate, match.start(1), match.end(1)
    return None


def _product_name(text: str) -> str | None:
    match = _product_match(text)
    return match[0] if match else None


def _reporter_present(folded: str) -> bool:
    reporter_roles = (
        "doctor", "physician", "nurse", "caregiver", "pharmacist", "clinician", "clinicians",
        "reporter", "reported", "reporting", "self-reported", "médico", "medico", "enfermera",
        "farmacéutico", "pharmacien", "médecin",
    )
    if any(_fold(term) in folded for term in reporter_roles):
        return True
    return bool(re.search(r"\b(?:patient|paciente)\s+(?:reports?|reported|reporting)\b", folded))


def classify(text: str) -> list[Classification]:
    normalized = " ".join(text.split())
    folded = _fold(normalized)
    labels: list[Classification] = []

    patient_present = bool(re.search(r"\b(?:patient|paciente|male|female|mujer|hombre|woman|man|\d{1,3}\s*[- ]?year[- ]?old|age\s*[:\-]?\s*\d{1,3})\b", folded))
    reporter_present = _reporter_present(folded)
    product_present = _product_name(normalized) is not None
    reaction_hits = _nonnegated_terms(normalized, REACTION_TERMS)

    if patient_present and reporter_present and product_present and reaction_hits:
        labels.append(Classification(
            category="ICSR",
            confidence=0.92,
            reason="Specific patient, reporter, product and non-negated adverse outcome indicators are present.",
        ))

    pqc_hits = _nonnegated_terms(normalized, PQC_TERMS) + _nonnegated_quality_patterns(normalized)
    if pqc_hits:
        labels.append(Classification(
            category="PQC",
            confidence=min(0.98, 0.76 + 0.04 * len(pqc_hits)),
            reason=f"Non-negated product-quality indicators detected: {', '.join(pqc_hits[:4])}.",
        ))

    question_like = "?" in normalized or any(_fold(term) in folded for term in MI_TERMS)
    if question_like and not reaction_hits and not pqc_hits:
        labels.append(Classification(
            category="MI",
            confidence=0.88,
            reason="The content asks a product/dosing question without a supported adverse reaction or product defect.",
        ))

    if not labels:
        labels.append(Classification(
            category="NOT_RELEVANT",
            confidence=0.84,
            reason="The four ICSR elements are incomplete and no supported PQC or MI pattern is present.",
        ))

    return labels


def _unknown() -> ExtractedValue:
    return ExtractedValue(value="Not stated", confidence=0.0, source=None)


def _value(located: Located | None, confidence: float, source_name: str, *, source_type: str = "PDF") -> ExtractedValue:
    if located is None:
        return _unknown()
    return ExtractedValue(
        value=located.value.strip(),
        confidence=confidence,
        source=SourceRef(source_type=source_type, source_name=source_name, page=located.page if source_type == "PDF" else None, evidence=located.evidence),
    )


def _regex(pages: list[str], pattern: str, group: int = 1, flags: int = re.I) -> Located | None:
    for page_number, text in enumerate(pages, start=1):
        match = re.search(pattern, text, flags)
        if match:
            return Located(match.group(group), page_number, _context(text, match.start(), match.end()))
    return None


def _term(pages: list[str], terms: tuple[str, ...], *, value_map: dict[str, str] | None = None) -> Located | None:
    for page_number, text in enumerate(pages, start=1):
        folded = _fold(text)
        for term in terms:
            target = _fold(term)
            for match in re.finditer(re.escape(target), folded):
                if _is_negated(folded, match.start()):
                    continue
                value = value_map.get(term, term) if value_map else term
                return Located(value, page_number, _context(text, match.start(), match.end()))
    return None


def _quality_pattern(pages: list[str]) -> Located | None:
    for page_number, text in enumerate(pages, start=1):
        folded = _fold(text)
        for pattern, label in PQC_PATTERNS:
            for match in re.finditer(pattern, folded, re.I):
                if _is_negated(folded, match.start()):
                    continue
                return Located(label, page_number, _context(text, match.start(), match.end()))
    return None


def _product(pages: list[str]) -> Located | None:
    for page_number, text in enumerate(pages, start=1):
        match = _product_match(text)
        if match:
            value, start, end = match
            return Located(value, page_number, _context(text, start, end))
    return None


def _question(pages: list[str]) -> Located | None:
    for page_number, text in enumerate(pages, start=1):
        for match in re.finditer(r"([^\n.!?]{8,}\?)", text):
            return Located(match.group(1).strip(), page_number, _context(text, match.start(), match.end()))
    return None


def _narrative(pages: list[str]) -> Located | None:
    """Return a source-grounded case narrative excerpt without generating or inferring text."""
    for page_number, text in enumerate(pages, start=1):
        sentence_matches = list(re.finditer(r"[^\n.!?]+(?:[.!?]|$)", text))
        for index, sentence_match in enumerate(sentence_matches):
            sentence = sentence_match.group(0).strip()
            if not sentence:
                continue
            folded_sentence = _fold(sentence)
            reaction_found = False
            for term in REACTION_TERMS:
                target = _fold(term)
                for hit in re.finditer(re.escape(target), folded_sentence):
                    if not _is_negated(folded_sentence, hit.start()):
                        reaction_found = True
                        break
                if reaction_found:
                    break
            if not reaction_found:
                continue

            selected = sentence
            start = sentence_match.start()
            if index > 0:
                previous_match = sentence_matches[index - 1]
                previous = previous_match.group(0).strip()
                previous_folded = _fold(previous)
                has_case_context = bool(
                    re.search(r"\b(?:patient|paciente|male|female|mujer|hombre|woman|man|\d{1,3}\s*[- ]?year[- ]?old)\b", previous_folded)
                    or _product_name(previous) is not None
                    or any(_fold(term) in previous_folded for term in REPORTER_TERMS)
                )
                if previous and has_case_context:
                    selected = f"{previous} {sentence}"
                    start = previous_match.start()

            selected = " ".join(selected.split())
            return Located(selected, page_number, _context(text, start, sentence_match.end(), radius=80))
    return None


def extract_facts(pages: list[str], source_name: str) -> dict[str, dict[str, ExtractedValue]]:
    age = _regex(pages, r"\b(\d{1,3})\s*[- ]?year[- ]?old\b") or _regex(pages, r"\bage\s*[:\-]?\s*(\d{1,3})\b")
    sex = _term(pages, ("female", "male", "woman", "man", "mujer", "hombre"), value_map={"woman":"female","mujer":"female","man":"male","hombre":"male"})
    weight = _regex(pages, r"\b(\d{2,3}\s*kg)\b")
    reporter = _term(pages, REPORTER_TERMS)
    country = _term(pages, COUNTRIES)
    product = _product(pages)
    dose = _regex(pages, r"\b(\d+(?:\.\d+)?\s*(?:mg|mcg|g|ml))\b")
    route = _term(pages, ROUTE_TERMS)
    start_date = _regex(pages, r"\b(?:started?|start date)\s*[:\-]?\s*(\d{1,2}[-/][A-Za-z]{3}[-/]\d{4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})\b")
    lot = _regex(pages, r"\b(?:lot|batch)\s*(?:number|no\.?|#)?\s*[:\-]?\s*([A-Z0-9\-]+)\b")
    reaction = _term(pages, REACTION_TERMS)
    outcome = _term(pages, ("recovered", "resolved", "recovering", "fatal", "unknown"))
    seriousness = _term(pages, ("death", "life-threatening", "hospitalized", "hospitalised", "hospitalization"))
    narrative = _narrative(pages)
    pqc_issue = _term(pages, PQC_TERMS) or _quality_pattern(pages)
    photo = _term(pages, ("photo", "image", "photograph"), value_map={"photo":"yes","image":"yes","photograph":"yes"})
    question = _question(pages)

    return {
        "patient": {
            "age": _value(age, 0.97, source_name),
            "sex": _value(sex, 0.94, source_name),
            "weight_height": _value(weight, 0.93, source_name),
            "relevant_history": _unknown(),
        },
        "reporter": {
            "identity_role": _value(reporter, 0.78, source_name),
            "country": _value(country, 0.90, source_name),
        },
        "product": {
            "name": _value(product, 0.95, source_name),
            "dose": _value(dose, 0.96, source_name),
            "route": _value(route, 0.90, source_name),
            "start_stop_dates": _value(start_date, 0.88, source_name),
            "batch_lot": _value(lot, 0.94, source_name),
        },
        "reaction": {
            "event": _value(reaction, 0.88, source_name),
            "onset": _unknown(),
            "outcome": _value(outcome, 0.86, source_name),
        },
        "severity": {
            "seriousness": _value(seriousness, 0.94, source_name),
        },
        "narrative": {
            "case_narrative": _value(narrative, 0.90, source_name),
        },
        "quality_complaint": {
            "issue": _value(pqc_issue, 0.90, source_name),
            "photo_mentioned": _value(photo, 0.85, source_name),
        },
        "medical_information": {
            "question": _value(question, 0.94, source_name),
            "product_topic": _value(product, 0.90, source_name),
        },
    }
