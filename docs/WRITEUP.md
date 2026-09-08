# Technical write-up — Smart Inbox Assistant

## 1. Problem and design goal

The assignment is fundamentally a document-understanding and classification workflow with unusually strong audit requirements. The system must accept email plus PDF evidence, decide whether the content is a Safety Report (ICSR), Product Quality Complaint (PQC), Medical Information request (MI), or Not Relevant, extract key facts, and give a human reviewer enough context to verify or correct the AI result quickly.

The most important design decision is therefore **human-in-the-loop first-pass automation**, not autonomous case adjudication. The application deliberately exposes confidence, evidence and missing information. A wrong confident guess is more harmful than an explicit gap, so unsupported fields are represented as `Not stated` rather than inferred.

## 2. Architecture and technology choices

The runtime follows the stack suggested in the assignment: Angular for the reviewer experience, Spring Boot/Java for mailbox intake and business orchestration, Python for PDF/OCR/LLM work, and Oracle for queryable operational/audit persistence.

Angular is a good fit because the reviewer workflow is stateful and form-oriented: reviewers move through a queue, inspect document metadata and AI output, edit fields, and accept or override a decision. Spring Boot owns the operational boundary because it is well suited to mailbox integration, REST APIs, transactions, Oracle/JPA/JDBC access, validation and durable background orchestration. Python owns document understanding because the strongest PDF/OCR/AI ecosystem is available there: PyPDF, PyMuPDF, pdfplumber, Pillow, Tesseract, Pydantic and HTTP clients for model gateways.

The Python service is independent behind HTTP. This prevents the Java service from becoming coupled to model-specific SDKs and makes it possible to deploy document workers with different CPU/GPU scaling characteristics.

Oracle stores messages, attachment/security metadata, durable jobs, classifications, extracted facts and audit/review history because the assignment explicitly requests it and because relational audit queries are valuable here. Field-level provenance is persisted as domain data rather than only rendered in the UI.

## 3. Mail ingestion and durable processing

The Spring service connects to an isolated test mailbox using IMAP/IMAPS credentials supplied only through environment variables. It reads unread messages, extracts sender, subject, received date and body, recursively walks multipart MIME content, captures attachments, and de-duplicates by the Internet `Message-ID`.

Every accepted message enters a database-backed `PROCESSING_JOB` workflow. Source attachment metadata and bytes are staged before the mail is treated as processed. Workers use compare-and-set job claims, bounded execution, retry/backoff, attempt limits and lease recovery so a process restart does not silently lose an in-flight item. Reprocessing is idempotent: prior AI classifications/facts are replaced rather than accumulated.

PDF-labeled content is checked for a PDF signature and size limits before parsing. Supported PDF bytes must pass a fail-closed ClamAV scan before they can reach AI/OCR. Scanner errors use the same durable retry path; malware is quarantined and never passed downstream. Clean originals are stored through a content-addressed document-store abstraction and reloaded bytes are verified against SHA-256 before use. Unsupported/invalid/oversized attachments remain queryable with processing/rejection state rather than disappearing.

The system records per-message and per-attachment processing time so the required batch-performance evidence can be produced.

## 4. PDF understanding strategy

The document service starts by extracting digital text. If little or no usable text is present, it renders the PDF and invokes Tesseract OCR, returning aggregate OCR confidence. This supplies a reproducible scanned/handwritten path while making OCR uncertainty visible.

For digital documents the service detects language and article-like structure. Published articles are identified using article markers such as abstract/case-report/references structure. Reference sections are removed before patient-case extraction so bibliography/general discussion does not become safety evidence.

Tables are extracted with pdfplumber into nested rows rather than flattened text. Those rows are persisted with the attachment and surfaced to the reviewer alongside page numbers.

Embedded visuals are extracted with PyMuPDF. When OCR finds machine-readable text inside an image, the finding contains that actual OCR evidence, OCR-derived confidence and image dimensions. When no reliable text is found, the system records the visual and dimensions but explicitly does **not** infer clinical/product meaning. Both cases carry a human-review flag. This is intentionally more defensible than a generic placeholder pretending to be a vision-model interpretation.

Non-English documents preserve original extracted page text. Translation has explicit `status`, `method`, `rationale`, source/target language and `requires_human_review`. In offline mode, deterministic Spanish/French translations are deliberately limited to the repository's synthetic fixture vocabulary. If a structured LLM endpoint is configured, it can translate page-by-page under a conservative prompt. Translation is auxiliary reviewer context only: extracted facts still have to cite evidence from the original source page. If translation is unavailable, the system says so instead of silently normalizing or guessing.

## 5. Classification and extraction

Classification is multi-label. The ICSR rule follows the assignment literally: a patient, reporter, specific/suspect product and adverse reaction/outcome must all be supported. The deterministic fallback is negation-aware so phrases such as `no death` do not become a death finding. PQC looks for physical product-quality defects. MI is used for product questions. Not Relevant is suppressed whenever another supported category is present.

For safety content the extractor returns patient, reporter, product, reaction, seriousness and related fields. PQC adds defect/batch/photo fields and MI adds the question/product topic. The system does not create invented values. A missing value has `Not stated`, zero confidence and no citation.

Every supported field has source type, file/message identifier, PDF page when applicable, evidence snippet and confidence. This is the most important audit property in the solution.

## 6. Prompting / LLM approach

The extraction prompt is treated as a machine-readable contract rather than a conversational request. It states the four classification definitions, allows multiple labels, explicitly forbids invention, defines the structured-field schema, requires source/evidence, instructs the model to ignore article references/general discussion and tells it how to handle non-English evidence.

The LLM integration is optional and environment-configured. This is intentional: CI and offline demonstrations must not depend on a paid external API. When enabled, returned JSON is validated with Pydantic. More importantly, claimed provenance is checked against the actual email/PDF pages. If a field cites evidence that is not present on the stated original page, that field is dropped. This prevents a syntactically valid hallucinated citation from being treated as audit evidence.

Translation uses a separate conservative structured prompt that preserves page boundaries, drug/product names, doses, units, dates, negation and uncertainty. Even then, translated text is marked as auxiliary and human-reviewable rather than accepted as authoritative source evidence.

The deterministic path remains available when no LLM is configured. In production I would add provider failover/rate-limit handling, model-version pinning, prompt/version tracking and a formal evaluation gate before model upgrades.

## 7. Confidence strategy

Confidence values are not marketed as calibrated clinical probabilities. In deterministic mode they are rule-strength indicators: exact structured matches have higher confidence than broad keyword matches, OCR confidence comes from OCR word confidence, and unsupported fields are exactly zero. Image OCR confidence is likewise derived from OCR tokens, while non-text visuals are assigned zero semantic confidence rather than an invented score. In the LLM path the model-provided extraction score is accepted only after structural/provenance validation.

A production system should calibrate confidence against a labeled validation set and track precision/recall, reviewer override rate, false-negative safety cases, per-field extraction accuracy and agreement by document type/language.

## 8. Human review and audit trail

The Angular UI shows the queue, status, classification confidence and reasons, AI summary, attachment characteristics and extracted fields. Every fact shows provenance. Attachment review also exposes OCR confidence, processing time, security/storage state, translation metadata with original-versus-English page text, structured tables and embedded-image findings. Translation/visual outputs that require verification are explicitly labeled for human review.

Reviewers can edit supported values and either accept or override. Corrections are stored in a separate reviewer-action history with timestamp, reviewer, previous value, new value and note instead of silently destroying AI history. In OIDC mode the authenticated principal is authoritative for reviewer identity; request JSON cannot spoof it. Audit entries carry request correlation identifiers.

The optional literature screen reuses the same principles. Article PDFs can be uploaded independently, numbered cases are split, each segment receives a reportability decision, confidence, reason, summary, classifications and provenance-backed facts.

## 9. Synthetic test data and testing

No real patient/client data is used. A deterministic generator creates 15 email fixtures, 5 digital PDFs, 2 image-only scan/handwriting PDFs, 5 fictional two-column articles, 2 non-English PDFs, PQC-only, MI-only, multi-label and irrelevant examples. The binary PDFs are generated locally from source so the repository does not need hand-maintained opaque fixture binaries.

A batch runner calls the document API, stores extracted JSON for every generated PDF, records both client and service processing time and writes an exact-classification report. GitHub Actions tests the Python service, Spring backend and Angular application, then gates a Docker Compose smoke test on those jobs. The smoke path starts Oracle, GreenMail, ClamAV, AI, backend and frontend, sends a synthetic PDF through SMTP, waits for IMAP ingestion and durable processing, verifies clean scan/storage/provenance, performs a protected reviewer `ACCEPT`, and exercises literature screening.

Unit/integration coverage includes classification/negation, missing facts, LLM provenance validation, translation transparency, embedded-image metadata, durable jobs, document storage/security, OIDC/RBAC/reviewer spoof prevention, reviewer persistence and enrichment projection.

## 10. Data handling and security trade-offs

Local deterministic mode and Tesseract keep synthetic content within the candidate machine/Compose stack. A cloud LLM improves language/layout reasoning but creates data-handling considerations: provider retention, training usage, geography, encryption, subprocessors and incident controls. A real healthcare/pharmacovigilance deployment should only enable an approved provider/model through a governed model gateway or use a private deployment. Data minimization and purpose limitation should be applied before any external model call.

Identity has two modes. `demo` preserves a simple isolated candidate workflow and can protect mutations with an API key. `oidc` enables OAuth2/JWT resource-server validation, issuer/audience/JWK checks and reviewer/admin role rules. Production browser authentication still needs organization-approved PKCE/BFF/session architecture and lifecycle controls.

Original-document storage is abstracted and integrity-checked. The filesystem implementation demonstrates content-addressed immutable semantics for the candidate stack; regulated deployment should replace it with approved encrypted object storage, key management, legal hold and validated retention/restore procedures.

## 11. Known limitations

The current implementation is a production-oriented prototype, not a validated safety system. Local OCR quality can be poor on difficult handwriting. Deterministic Spanish/French translation is deliberately limited to synthetic fixture vocabulary, while configured LLM translation still needs human verification and formal medical-translation validation before production use. Non-text visuals are detected/flagged rather than semantically interpreted; a production vision capability would need a validated model, confidence calibration and reviewer SOPs. Confidence is not statistically calibrated. Filesystem source storage, demo authentication and local Docker orchestration are candidate-environment implementations rather than regulated enterprise controls.

These limitations are surfaced because they drive the production backlog rather than being hidden behind a polished UI.

## 12. What I would change for production

The durable queue, malware gate, SHA-integrity verification, content-addressed source-store abstraction, OIDC boundary, correlation/metrics and retry/lease behavior already establish useful operational seams. A production deployment would move those seams onto organization-approved infrastructure: managed Oracle and/or broker semantics as appropriate, encrypted immutable object storage, centralized keys/secrets, TLS/mTLS, enterprise IdP/session architecture, SIEM/trace export, SLO dashboards, retention/legal-hold policy and tested backup/restore/DR.

For AI quality I would create a labeled, versioned evaluation set across document types/languages, add prompt/model/version fields to every decision, block deployment if safety recall regresses, track reviewer overrides and calibrate field/classification confidence. High-risk handwriting, unavailable translation or low-confidence visual/text extraction would automatically route to an approved higher-capability service or mandatory manual entry rather than silently degrade.

Finally, for a regulated production environment I would establish organization-specific QMS/SOP validation, change-control evidence, access reviews, privacy/data-governance approvals, threat modeling and penetration testing, disaster-recovery exercises, audit-log retention/immutability, model/vendor risk assessment and documented human-review procedures.
