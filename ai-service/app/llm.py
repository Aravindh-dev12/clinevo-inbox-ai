from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

import httpx

from .models import AiDecision, ExtractedValue

PROMPT_PATH = Path(__file__).resolve().parents[1] / "prompts" / "classify_extract.md"


class StructuredLlmClient:
    """Optional OpenAI-compatible chat-completions client.

    Disabled unless both LLM_API_URL and LLM_MODEL are configured. The deterministic
    fallback remains the default for tests and offline demonstrations.
    """

    def __init__(self) -> None:
        self.url = os.getenv("LLM_API_URL", "").strip()
        self.model = os.getenv("LLM_MODEL", "").strip()
        self.api_key = os.getenv("LLM_API_KEY", "").strip()
        self.timeout = float(os.getenv("LLM_TIMEOUT_SECONDS", "45"))

    @property
    def enabled(self) -> bool:
        return bool(self.url and self.model)

    def decide(self, source_text: str) -> AiDecision | None:
        if not self.enabled:
            return None
        system_prompt = PROMPT_PATH.read_text(encoding="utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        body = {
            "model": self.model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": source_text},
            ],
        }
        try:
            response = httpx.post(self.url, headers=headers, json=body, timeout=self.timeout)
            response.raise_for_status()
            content = _extract_content(response.json())
            return AiDecision.model_validate_json(content)
        except (httpx.HTTPError, ValueError, KeyError, TypeError, json.JSONDecodeError):
            return None


def _extract_content(payload: dict[str, Any]) -> str:
    choices = payload.get("choices")
    if isinstance(choices, list) and choices:
        message = choices[0].get("message", {})
        content = message.get("content")
        if isinstance(content, str):
            return content
    output_text = payload.get("output_text")
    if isinstance(output_text, str):
        return output_text
    content = payload.get("content")
    if isinstance(content, str):
        return content
    raise ValueError("LLM response did not contain JSON text")


def validate_provenance(decision: AiDecision, *, file_name: str, pages: list[str], email_text: str) -> AiDecision:
    """Discard unsupported values instead of trusting model citations blindly."""
    for group in decision.extracted_facts.values():
        for field, value in list(group.items()):
            if value.value.strip().lower() in {"not stated", "unknown", ""}:
                group[field] = ExtractedValue(value="Not stated", confidence=0.0, source=None)
                continue
            source = value.source
            if source is None or not source.evidence:
                group[field] = ExtractedValue(value="Not stated", confidence=0.0, source=None)
                continue
            evidence = " ".join(source.evidence.split()).casefold()
            if source.source_type == "EMAIL":
                haystack = " ".join(email_text.split()).casefold()
                supported = evidence in haystack
                page_valid = source.page is None
            else:
                page_valid = source.source_name == file_name and source.page is not None and 1 <= source.page <= len(pages)
                haystack = " ".join(pages[source.page - 1].split()).casefold() if page_valid else ""
                supported = evidence in haystack
            if not (supported and page_valid):
                group[field] = ExtractedValue(value="Not stated", confidence=0.0, source=None)
    return decision
