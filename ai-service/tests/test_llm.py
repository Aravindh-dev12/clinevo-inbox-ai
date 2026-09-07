from app.llm import validate_provenance
from app.models import AiDecision, Classification, ExtractedValue, SourceRef


def test_unsupported_llm_fact_is_discarded():
    decision = AiDecision(
        classifications=[Classification(category="ICSR", confidence=.9, reason="test")],
        summary="synthetic summary",
        extracted_facts={"patient": {"age": ExtractedValue(value="99", confidence=.9, source=SourceRef(source_type="PDF", source_name="case.pdf", page=1, evidence="age 99"))}},
    )
    validated = validate_provenance(decision, file_name="case.pdf", pages=["Patient age 45."], email_text="")
    assert validated.extracted_facts["patient"]["age"].value == "Not stated"
    assert validated.extracted_facts["patient"]["age"].source is None


def test_supported_llm_fact_is_retained():
    decision = AiDecision(
        classifications=[Classification(category="ICSR", confidence=.9, reason="test")],
        summary="synthetic summary",
        extracted_facts={"patient": {"age": ExtractedValue(value="45", confidence=.9, source=SourceRef(source_type="PDF", source_name="case.pdf", page=1, evidence="Patient age 45"))}},
    )
    validated = validate_provenance(decision, file_name="case.pdf", pages=["Patient age 45. Synthetic case."], email_text="")
    assert validated.extracted_facts["patient"]["age"].value == "45"
