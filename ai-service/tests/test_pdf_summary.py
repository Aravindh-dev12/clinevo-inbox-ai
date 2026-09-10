from types import SimpleNamespace

from app.pdf_processor import _summary, _summary_sentence_count


def test_pdf_summary_is_ten_sentences_when_ocr_text_lacks_terminal_punctuation():
    source_text = (
        "Synthetic OCR source identifies a patient reporter product dose and adverse event "
        "but the OCR stream ends without terminal punctuation"
    )
    classifications = [SimpleNamespace(category="ICSR")]

    summary = _summary(source_text, classifications)

    assert _summary_sentence_count(summary) == 10
    assert summary.endswith(".")


def test_pdf_summary_sentence_count_guard_accepts_assignment_range_only():
    ten_sentences = " ".join(f"Sentence {index}." for index in range(10))
    fifteen_sentences = " ".join(f"Sentence {index}." for index in range(15))
    nine_sentences = " ".join(f"Sentence {index}." for index in range(9))
    sixteen_sentences = " ".join(f"Sentence {index}." for index in range(16))

    assert 10 <= _summary_sentence_count(ten_sentences) <= 15
    assert 10 <= _summary_sentence_count(fifteen_sentences) <= 15
    assert not 10 <= _summary_sentence_count(nine_sentences) <= 15
    assert not 10 <= _summary_sentence_count(sixteen_sentences) <= 15
