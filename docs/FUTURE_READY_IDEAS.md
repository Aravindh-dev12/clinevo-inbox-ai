# Future-ready extension ideas

This document captures optional, assignment-aligned ideas that make the prototype feel forward-looking without pretending it is a validated pharmacovigilance production system.

## Near-term prototype enhancements

1. **Risk-aware reviewer triage** — rank items by safety relevance, uncertainty, OCR/translation risk, image-review flags, and missing evidence instead of simple arrival order.
2. **Evidence coverage score** — show how much of the extracted structured record has page-level or email-level supporting evidence so reviewers can immediately see weak spots.
3. **Reviewer feedback loop** — aggregate accepts, category overrides, and field corrections into a versioned evaluation dataset for regression testing and future model calibration.
4. **Decision manifest** — persist model/prompt/rule version, pipeline version, input hash, and timestamp beside each AI decision to make model lifecycle changes auditable.
5. **E2B(R3) export adapter** — map reviewed ICSR fields to an interoperability-oriented E2B(R3) representation as an optional downstream adapter rather than coupling the intake model directly to one regulatory schema.
6. **Adaptive model routing** — keep deterministic/local processing for straightforward cases, escalate low-confidence handwriting, difficult multilingual content, or complex layouts to a stronger approved model, and require human review when escalation is unavailable.
7. **Counterfactual reviewer assistance** — explain which missing minimum elements prevented an ICSR classification (for example reporter or suspect product) rather than only showing a confidence number.
8. **Drift and override telemetry** — monitor classification precision/recall on a fixed validation set and track reviewer override rates by document type, language, model version, and source.

## Design guardrail

These ideas are deliberately human-centric and risk-based. They should improve reviewer attention and auditability, not create autonomous case adjudication. Any real regulated deployment would still require validated procedures, controlled model/version changes, security/privacy governance, and organization-specific pharmacovigilance SOPs.
