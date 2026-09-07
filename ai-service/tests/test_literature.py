import fitz
from fastapi.testclient import TestClient

from app.literature import screen_literature_pdf
from app.main import app


def make_pdf(text: str) -> bytes:
    document = fitz.open()
    page = document.new_page(width=595, height=842)
    page.insert_textbox(fitz.Rect(40, 40, 555, 802), text, fontsize=10)
    data = document.tobytes()
    document.close()
    return data


def test_splits_multiple_reportable_cases_and_keeps_page_provenance():
    data = make_pdf(
        "Abstract\nSynthetic case series.\n"
        "Case 1: A 41-year-old female patient was reported by a physician after Drug Alpha 10 mg and developed rash. She recovered.\n"
        "Case 2: A 52-year-old male patient was reported by a nurse after Drug Beta 5 mg and developed nausea. He recovered.\n"
        "References\n1. Synthetic reference only."
    )
    result = screen_literature_pdf("case-series.pdf", data)
    assert result.case_count == 2
    assert all(item.reportable for item in result.cases)
    assert all(item.source_pages == [1] for item in result.cases)
    assert result.cases[0].extracted_facts["patient"]["age"].source.page == 1


def test_non_case_article_is_not_reportable():
    data = make_pdf("Abstract\nThis synthetic review discusses dosing trends in general. No individual patient case is described.\nReferences\n1. Synthetic reference.")
    result = screen_literature_pdf("review.pdf", data)
    assert result.case_count == 1
    assert result.cases[0].reportable is False


def test_batch_api_accepts_multiple_articles():
    client = TestClient(app)
    one = make_pdf("Case 1: A 33-year-old female patient was reported by a doctor after Drug Gamma 5 mg and developed dizziness.")
    two = make_pdf("Case 1: General product discussion without an identifiable patient case.")
    response = client.post(
        "/literature/screen",
        files=[
            ("files", ("one.pdf", one, "application/pdf")),
            ("files", ("two.pdf", two, "application/pdf")),
        ],
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["total_documents"] == 2
    assert payload["total_cases"] == 2
