# Technical write-up — Smart Inbox Assistant

## 1. Problem and design goal

The assignment is fundamentally a document-understanding and classification workflow with unusually strong audit requirements. The system must accept email plus PDF evidence, decide whether the content is a Safety Report (ICSR), Product Quality Complaint (PQC), Medical Information request (MI), or Not Relevant, extract key facts, and give a human reviewer enough context to verify or correct the AI result quickly.

The most important design decision is therefore **human-in-the-loop first-pass automation**, not autonomous case adjudication. The application deliberately exposes confidence, evidence and missing information. A wrong confident guess is more harmful than an explicit gap, so unsupported fields are represented as `Not stated` rather than inferred.

## 2. Architecture and technology choices

The runtime follows the stack suggested in the assignment: Angular for the reviewer experience, Spring Boot/Java for mailbox intake and business orchestration, Python for PDF/OCR/LLM work, and Oracle for queryable operational/audit persistence.

Angular is a good fit because the reviewer workflow is stateful and form-oriented: reviewers move through a queue, inspect document metadata and AI output, edit fields, and accept or override a decision. Spring Boot owns the operational boundary because it is well suited to mailbox integration, REST APIs, transactions, Oracle/JPA/JDBC access, validation and background orchestration. Python owns document understanding because the strongest PDF/OCR/AI ecosystem is available there: PyPDF, PyMuPDF, pdfplumber, Pillow, Tesseract, Pydantic and HTTP clients for model gateways.

The Python service is kept independent behind HTTP. This prevents the Java service from becoming coupled to model-specific SDKs and makes it possible to deploy document workers with different CPU/GPU scaling characteristics.

Oracle stores messages and results because the assignment explicitly requests it and because relational audit queries are valuable here. Field-level provenance is persisted as domain data rather than only rendered in the UI.

## 3. Mail ingestion and asynchronous processing

The Spring service connects to an isolated test mailbox using IMAP/IMAPS credentials supplied only through environment variables. It reads unread messages, extracts sender, subject, received date and body, recursively walks multipart MIME content, captures attachments, and de-duplicates by the Internet `Message-ID`.

PDF attachments are sent to the AI service; unsupported types are logged rather than parsed. The current candidate implementation uses a **bounded process-local executor**. This keeps mailbox polling responsive while OCR/AI calls run for seconds, and unlike an unbounded executor it applies a finite concurrency limit. It is intentionally documented as a prototype compromise. A production version should use a durable queue or database-backed job claim model with explicit retry/backoff/dead-letter behavior.

The system records per-message and per-attachment processing time so the required batch-performance evidence can be produced.

## 4. PDF understanding strategy

The document service starts by extracting digital text. If little or no usable text is present, it renders the PDF and invokes Tesseract OCR, returning aggregate OCR confidence. This satisfies the scanned/handwritten path without pretending that local OCR is reliable enough for all handwriting.

For digital documents the service detects language and article-like structure. Published articles are identified using article markers such as abstract/case-report/references structure. Reference sections are removed before patient-case extraction so bibliography/general discussion does not become safety evidence.

Tables are extracted with pdfplumber into nested rows rather than flattened text. Embedded images are detected with PyMuPDF and returned as review findings. The prototype does not perform deep clinical image interpretation; it tells the reviewer where meaningful images exist and that they require human inspection.

Non-English content is language-tagged. The deterministic fallback has basic Spanish/French vocabulary coverage for synthetic tests; richer multilingual normalization is expected to come from the structured LLM path or a dedicated translation service in production.

## 5. Classification and extraction

Classification is multi-label. The ICSR rule follows the assignment literally: a patient, reporter, specific/suspect product and adverse reaction/outcome must all be supported. The deterministic fallback is negation-aware so phrases such as `no death` do not become a death finding. PQC looks for physical product-quality defects. MI is used for product questions when no supported adverse reaction or defect exists. Not Relevant is only used when the other categories are unsupported.

For safety content the extractor returns patient, reporter, product, reaction, seriousness and related fields. PQC adds defect/batch/photo fields and MI adds the question/product topic. The system does not create invented values. A missing value has `Not stated`, zero confidence and no citation.

Every supported field has source type, file/message identifier, PDF page when applicable, evidence snippet and confidence. This is the most important audit property in the solution.

## 6. Prompting / LLM approach

The prompt is treated as a machine-readable contract rather than a conversational request. It states the four classification definitions, allows multiple labels, explicitly forbids invention, defines the exact structured-field schema, requires source/evidence, instructs the model to ignore article references/general discussion and tells it how to handle non-English evidence.

The LLM integration is optional and environment-configured. This is intentional: CI and offline demonstrations must not depend on a paid external API. When enabled, returned JSON is validated with Pydantic. More importantly, claimed provenance is checked against the actual email/PDF pages. If a field cites evidence that is not present on the stated page, that field is dropped. This prevents a syntactically valid hallucinated citation from being treated as audit evidence.

The deterministic path remains a fallback even when an LLM is available. In production I would add provider failover, rate-limit handling, model-version pinning, prompt/version tracking and a formal evaluation gate before model upgrades.

## 7. Confidence strategy

Confidence values are not marketed as calibrated clinical probabilities. In deterministic mode they are rule-strength indicators: exact structured matches have higher confidence than broad keyword matches, OCR confidence comes from OCR word confidence, and unsupported fields are exactly zero. In the LLM path the model-provided score is accepted only after structural/provenance validation.

A production system should calibrate confidence against a labeled validation set and track precision/recall, reviewer override rate, false-negative safety cases, per-field extraction accuracy and agreement by document type/language.

## 8. Human review and audit trail

The Angular UI shows the queue, status, classification confidence and reasons, AI summary, attachment characteristics and extracted fields. Every fact shows provenance. Reviewers can edit supported values and either accept or override. Corrections are stored in a separate reviewer-action history with timestamp, reviewer, previous value, new value and note instead of silently destroying AI history.

The optional literature screen reuses the same principles. Article PDFs can be uploaded independently, numbered cases are split, each segment receives a reportability decision, confidence, reason, summary, classifications and provenance-backed facts.

## 9. Synthetic test data and testing

No real patient/client data is used. A deterministic generator creates 15 email fixtures, 5 digital PDFs, 2 image-only scan/handwriting PDFs, 5 fictional two-column articles, 2 non-English PDFs, PQC-only, MI-only, multi-label and irrelevant examples. The binary PDFs are generated locally from source so the repository does not need hand-maintained opaque fixture binaries.

A batch runner calls the document API, stores extracted JSON for every generated PDF, records both client and service processing time and writes an exact-classification report. GitHub Actions separately tests the Python service, Spring backend and Angular application.

## 10. Data handling trade-offs

Local deterministic mode and Tesseract keep synthetic data on the local machine. A cloud LLM improves language/layout reasoning but creates data-handling considerations: provider retention, training usage, geography, encryption, subprocessors and incident controls. A real healthcare/pharmacovigilance deployment should only enable an approved provider/model through a governed model gateway or use a private deployment. Data minimization and purpose limitation should be applied before any external model call.

The demo API-key guard protects accidental writes in an isolated environment but is not production identity. Production requires OIDC/OAuth2, reviewer/admin roles and enterprise identity lifecycle management.

## 11. Known limitations

The current implementation is intentionally a production-oriented prototype, not a validated safety system. Local OCR quality can be poor on difficult handwriting. Image handling is a good-faith flag/description rather than diagnostic image analysis. The queue is bounded but not durable across process restarts. Original attachment bytes are not yet placed in an immutable enterprise object store. Deterministic non-English handling is limited. Confidence is not statistically calibrated. The API-key mechanism is a demo control rather than user identity.

These limitations are surfaced because they drive the production backlog rather than being hidden behind a polished UI.

## 12. What I would change for production

I would first introduce a durable job model: persist the original document before processing, publish/claim a job using an idempotency key, retry transient AI/OCR failures with exponential backoff, and route repeated failures to a dead-letter queue for operations review. Originals would be written to approved encrypted object storage with immutable hash, retention class and legal-hold support.

Next I would replace the shared API key with OIDC/OAuth2 and RBAC, put every service behind TLS/mTLS, move secrets to a vault, add malware scanning and content-type verification, export structured audit events to the enterprise SIEM, and add OpenTelemetry traces plus SLO dashboards.

For AI quality I would create a labeled, versioned evaluation set across document types/languages, add prompt/model version fields to every decision, block deployment if safety recall regresses, track reviewer overrides and calibrate field/classification confidence. High-risk handwriting or low-confidence fields would be automatically routed to a higher-capability vision model or mandatory manual entry.

Finally, for a regulated production environment I would establish validation/change-control evidence, access reviews, backup/restore tests, disaster recovery, audit-log retention, model/vendor risk assessment and documented human-review SOPs.
