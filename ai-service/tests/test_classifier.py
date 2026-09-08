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


def test_mi_only():
    assert categories("Can Clinevex 10 mg be taken with food? No adverse event or defect was reported.") == {"MI"}


def test_multilabel_icsr_and_pqc():
    text = "Patient P8 used Product Novera 20 mg. Patient reported swelling. The syringe was cracked and leaking."
    assert categories(text) == {"ICSR", "PQC"}


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
