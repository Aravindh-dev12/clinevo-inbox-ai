import io

import fitz
from PIL import Image

from app.llm import StructuredLlmClient
from app.pdf_processor import _extract_images, _translate_pages


def test_spanish_synthetic_translation_preserves_original_and_explains_mode(monkeypatch):
    monkeypatch.delenv("LLM_API_URL", raising=False)
    monkeypatch.delenv("LLM_MODEL", raising=False)
    page = (
        "Informe de seguridad sintético\n"
        "Paciente P-ES1, mujer de 44 años.\n"
        "Reportante Médico tratante en España.\n"
        "Producto Clinevex 10 mg por vía oral una vez al día.\n"
        "Reacción Dos días después de iniciar el tratamiento presentó erupción cutánea y picor.\n"
        "Resultado El medicamento se suspendió y la paciente se recuperó en 48 horas.\n"
        "Aviso Todos los datos son ficticios y se usan únicamente para pruebas."
    )

    result = _translate_pages([page], "es", StructuredLlmClient())

    assert result.applied is True
    assert result.status == "TRANSLATED"
    assert result.method == "SYNTHETIC_RULES"
    assert result.requires_human_review is True
    assert result.pages[0].original_text == page
    assert "44-year-old female" in result.pages[0].translated_text
    assert "skin rash and itching" in result.pages[0].translated_text
    assert "demonstration/testing only" in result.rationale


def test_unknown_language_is_not_silently_translated(monkeypatch):
    monkeypatch.delenv("LLM_API_URL", raising=False)
    monkeypatch.delenv("LLM_MODEL", raising=False)

    result = _translate_pages(["short source"], "unknown", StructuredLlmClient())

    assert result.applied is False
    assert result.status == "UNAVAILABLE"
    assert result.method == "UNAVAILABLE"
    assert result.requires_human_review is True
    assert result.pages[0].original_text == "short source"
    assert result.pages[0].translated_text == ""


def test_embedded_image_finding_uses_actual_dimensions_and_requires_review():
    image = Image.new("RGB", (320, 180), "white")
    png = io.BytesIO()
    image.save(png, format="PNG")

    document = fitz.open()
    page = document.new_page(width=500, height=500)
    page.insert_image(fitz.Rect(50, 50, 370, 230), stream=png.getvalue())
    pdf = document.tobytes()
    document.close()

    findings = _extract_images(pdf)

    assert len(findings) == 1
    assert findings[0].page == 1
    assert findings[0].width == 320
    assert findings[0].height == 180
    assert findings[0].requires_human_review is True
    assert findings[0].method in {"OCR_TEXT", "METADATA_ONLY"}
    assert "human" in findings[0].description.lower() or "reviewer" in findings[0].description.lower()
