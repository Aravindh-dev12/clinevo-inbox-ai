# Testing and evaluation evidence

## CI gates

Every pull request runs independent AI, backend and frontend jobs.

### AI service

```bash
cd ai-service
pip install -r requirements.txt
pytest -q tests
```

Coverage includes:

- ICSR four-element minimum rule
- PQC classification
- MI classification
- multi-label ICSR + PQC
- negation (`no death` must not become death)
- missing fields remain `Not stated`
- page-level provenance
- structured-LLM provenance rejection
- API file validation
- literature multi-case splitting
- literature reportability and batch endpoint

### Backend

```bash
cd backend
mvn -B test
```

The backend test profile uses H2 so CI does not depend on an Oracle service. Tests cover service/reviewer persistence, API-key behavior, validation and literature batch guards while the production datasource remains Oracle.

### Frontend

```bash
cd frontend
npm install --no-audit --no-fund
npm run build
npm test -- --no-progress
```

The Angular production build is a CI gate in addition to headless component tests.

## Synthetic corpus coverage

Run:

```bash
pip install -r samples/requirements.txt
python samples/scripts/generate_corpus.py
```

The generator produces:

- 15 synthetic emails
- 5 normal digital PDFs
- 2 scanned/handwritten-style PDFs
- 5 fictional article PDFs
- 2 non-English PDFs
- at least 2 PQC-only examples
- at least 2 MI-only examples
- clearly irrelevant material
- multi-label ICSR + PQC cases
- tables in safety documents

No real patient, reporter or client data is permitted.

## Required batch performance report

Start the AI service, then run:

```bash
python samples/scripts/run_batch.py --url http://localhost:8000
```

Outputs:

```text
samples/outputs/
  <document>.json       extracted JSON for each PDF
  batch_report.csv      expected vs predicted + client/service timing
  summary.json          document count, exact matches, mean/max timing
```

The runner records wall-clock client latency and the AI service's internal `processing_ms` separately. Do not hard-code benchmark numbers into documentation; capture them from the exact laptop/model configuration used in the final walkthrough.

## Manual end-to-end acceptance test

1. Start `docker compose up --build`.
2. Confirm backend and AI health endpoints are green.
3. Generate the corpus.
4. Configure an isolated test mailbox.
5. Send at least ten synthetic email fixtures to it.
6. Wait for mailbox polling and verify queue entries become `READY_FOR_REVIEW`.
7. Open an ICSR message and verify classification + confidence + reason.
8. Verify extracted facts include source file/page/evidence.
9. Verify an omitted fact displays as missing rather than invented.
10. Open a PQC-only example and confirm it is not incorrectly forced into ICSR.
11. Open an MI-only example and verify the actual question is extracted.
12. Review a combined ICSR+PQC example and confirm multiple labels appear.
13. Accept one AI result and confirm a timestamped review action.
14. Override another field/category and confirm old/new values are visible in audit history.
15. Upload the five synthetic literature articles in the independent literature section and verify case splitting/relevance output.
16. Run the batch script and include its generated JSON/timing files with the submission.

## Regression rule

A PR should not be merged when any of the three CI jobs fail. For model/prompt changes, also run the synthetic batch and investigate any exact-classification regression before merging.
