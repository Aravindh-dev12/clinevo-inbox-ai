from app.classifier import classify, extract_facts


def categories(text: str) -> set[str]:
    return {item.category for item in classify(text)}


def test_icsr_requires_four_elements():
    complete = "Patient P1 is a 45-year-old female. Dr Smith reported Clinevex 10 mg. She developed rash."
    incomplete = "Patient P1 developed rash yesterday. Dr Smith reported it, but the suspect medicine was not provided."
    assert categories(complete) == {"ICSR"}
    assert categories(incomplete) == {"NOT_RELEVANT"}


def test_negated_seriousness_is_not_treated_as_event():
    text = "Patient P1 took Clinevex 10 mg. Physician report: rash resolved. No hospitalization, no life-threatening event, no death."
    facts = extract_facts([text], "case.pdf")
    assert facts["severity"]["seriousness"].value == "Not stated"
    assert facts["reaction"]["event"].value == "rash"


def test_hyphenated_age_and_page_provenance():
    facts = extract_facts(["Cover page only.", "Patient P2 is a 67-year-old male. Product Novera 20 mg. Nurse reported dizziness."], "case.pdf")
    assert facts["patient"]["age"].value == "67"
    assert facts["patient"]["age"].source.page == 2
    assert facts["product"]["name"].value == "Novera"
    assert facts["product"]["name"].source.page == 2


def test_pqc_only():
    assert categories("Product Dermacline batch LOT-Q100 has a broken seal and wrong color. No patient used it.") == {"PQC"}


def test_pqc_detects_natural_broken_seal_word_order():
    assert categories("Product Dermacline gel batch LOT-A17. The tube seal was broken before first use. No patient used it.") == {"PQC"}


def test_mi_only():
    assert categories("Can Clinevex 10 mg be taken with food? No adverse event or defect was reported.") == {"MI"}


def test_longer_negated_quality_phrase_does_not_turn_mi_into_pqc():
    text = "Product Clinevex 10 mg. Can it be taken with food? No adverse event or product defect was reported."
    assert categories(text) == {"MI"}


def test_multilabel_icsr_and_pqc():
    text = "Patient P8 used Product Novera 20 mg. Patient reported swelling. The syringe was cracked and leaking."
    assert categories(text) == {"ICSR", "PQC"}


def test_device_product_without_dose_supports_multilabel_case():
    text = (
        "A 40-year-old woman used a Novera autoinjector from lot AUTO-44. "
        "The needle shield was visibly damaged. She experienced swelling. "
        "A pharmacist in Singapore reported the case."
    )
    assert categories(text) == {"ICSR", "PQC"}


def test_first_named_product_dose_supports_article_icsr():
    text = (
        "Patient A4, a 22-year-old man, developed nausea two hours after the first Clinevex dose. "
        "The case was reported by clinicians in Spain."
    )
    assert categories(text) == {"ICSR"}


def test_product_extraction_skips_quality_heading():
    facts = extract_facts([
        "Synthetic Product Quality Complaint. Product Dermacline topical gel, batch LOT-A17. The seal was broken."
    ], "pqc.pdf")
    assert facts["product"]["name"].value == "Dermacline"


def test_spanish_synthetic_case_has_icsr_fallback():
    text = "Paciente P-ES1, mujer de 44 year-old. Médico reportó Producto Clinevex 10 mg. Presentó erupción y picor."
    assert "ICSR" in categories(text)


def test_mi_question_is_extracted_with_page():
    facts = extract_facts(["General page.", "Product Clinevex. Can Clinevex be taken with food?"], "mi.pdf")
    question = facts["medical_information"]["question"]
    assert question.value.endswith("?")
    assert question.source.page == 2


def test_icsr_narrative_is_source_grounded_with_provenance():
    text = "Patient P9 is a 51-year-old female taking Product Novera 20 mg. Nurse Lee reported that the patient developed dizziness and recovered later."
    facts = extract_facts([text], "case.pdf")
    narrative = facts["narrative"]["case_narrative"]
    assert narrative.value != "Not stated"
    assert "dizziness" in narrative.value.lower()
    assert narrative.source is not None
    assert narrative.source.page == 1
    assert "dizziness" in narrative.source.evidence.lower()


def test_narrative_is_not_stated_without_nonnegated_reaction():
    text = "Patient P10 used Product Novera 20 mg. Physician reported no rash and no dizziness."
    facts = extract_facts([text], "case.pdf")
    narrative = facts["narrative"]["case_narrative"]
    assert narrative.value == "Not stated"
    assert narrative.confidence == 0.0
    assert narrative.source is None
