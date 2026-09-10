# Future-ready extension ideas

The assignment asks for a working human-review prototype, so these ideas are intentionally practical rather than speculative. They also follow the current regulatory direction toward human-centric, risk-based AI with clear context of use, provenance, lifecycle management, and standards-based interoperability.

## Implemented in the reviewer cockpit

1. **Risk-aware reviewer triage** — queue ordering now uses transparent workflow/quality signals: safety-bearing ICSR content, multi-label cases, low classification confidence, weak OCR, non-English sources, incomplete provenance, and processing failures.
2. **Evidence coverage signal** — asserted facts are checked for source type/name and PDF page provenance; the queue exposes the resulting coverage to help reviewers find weak evidence quickly.
3. **Classification + confidence + summary in the queue** — the inbox itself now exposes the information required by the assignment instead of making the reviewer open every item first.
4. **Browser-rendered evidence gate** — CI verifies that Angular actually bootstraps in a real headless browser before accepting a screenshot, preventing a blank/mock evidence artifact from being treated as proof.

The priority score is explicitly **reviewer attention routing, not clinical seriousness or regulatory reportability**. A human reviewer remains authoritative.

## Next-generation production evolution

1. **Reviewer feedback loop** — aggregate accepts, category overrides, and field corrections into a versioned evaluation dataset for regression testing and confidence calibration.
2. **Decision manifest** — persist model/prompt/rule version, pipeline version, input hash, and timestamp beside each AI decision so model lifecycle changes are auditable.
3. **E2B(R3) export adapter** — map a reviewed ICSR into an interoperability-oriented downstream representation without coupling intake extraction directly to one transmission schema.
4. **Adaptive model routing** — keep deterministic/local processing for straightforward cases and escalate difficult handwriting, multilingual content, complex layouts, or low-confidence results to a stronger *approved* model with mandatory review.
5. **Counterfactual review assistance** — show exactly which minimum ICSR element is unsupported (patient, reporter, product, or event) instead of only emitting a confidence score.
6. **Drift and override telemetry** — monitor fixed-set performance and reviewer override rates by document type, language, pipeline/model version, and source channel.

## Regulatory design guardrail

FDA and EMA's January 2026 good-AI principles emphasize human-centric design, risk-based validation/oversight, standards, clear context of use, data governance/documentation, performance assessment, lifecycle management, and clear information. ICH E2B(R3) remains the relevant interoperability direction for electronic ICSR exchange. These principles support the architecture above, but they do **not** make this candidate prototype a validated pharmacovigilance production system.

A real deployment would still require organization-specific QMS/SOP validation, controlled model/version changes, privacy/security governance, approved infrastructure and model providers, calibrated evaluation datasets, operational monitoring, and formal validation of downstream regulatory mappings.
