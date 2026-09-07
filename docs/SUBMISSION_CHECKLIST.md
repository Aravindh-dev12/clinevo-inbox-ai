# Submission checklist

## Required deliverables

- [x] Working Angular frontend
- [x] Working Spring Boot backend
- [x] Oracle schema / Flyway migration
- [x] Python AI/PDF/OCR service
- [x] Test-mailbox integration
- [x] Multi-label ICSR/PQC/MI/Not Relevant logic
- [x] Digital PDF support
- [x] Scanned/handwritten OCR path + confidence
- [x] Published-article handling
- [x] Non-English detection
- [x] Structured table extraction
- [x] Image review flags/descriptions
- [x] Safety/PQC/MI structured fact extraction
- [x] Field-level source provenance
- [x] Human accept/override workflow
- [x] Timestamped audit history
- [x] Synthetic corpus generator
- [x] Batch JSON + timing runner
- [x] Optional literature screening extension
- [x] CI for AI/backend/frontend
- [x] README with local setup and environment placeholders
- [x] Architecture diagram
- [x] 2–5 page equivalent technical write-up
- [x] Prompting/confidence/data-handling/limitations documented
- [ ] Run `samples/scripts/run_batch.py` on the final demonstration machine and include generated `samples/outputs/`
- [ ] Capture final UI screenshots or a short screen recording from the running build

## Screenshot / recording shot list

Capture from the actual running application; do not use mockups.

1. Inbox queue with several synthetic messages and statuses.
2. ICSR message showing classifications, confidence and summary.
3. Extracted safety fields with PDF page/evidence provenance.
4. PQC-only or MI-only message showing correct category separation.
5. Reviewer override and audit trail with timestamp.
6. Scanned/handwritten example showing OCR confidence.
7. Literature screening page showing multiple cases from an article.
8. `samples/outputs/batch_report.csv` or `summary.json` showing measured processing times.
9. Optional: terminal with healthy Docker Compose services.

## Final repository checks

```bash
# no secrets
find . -maxdepth 3 -type f \( -name '.env' -o -name '*.pem' -o -name '*.key' \) -print

# tests
(cd ai-service && pytest -q tests)
(cd backend && mvn -B test)
(cd frontend && npm run build && npm test -- --no-progress)

# full stack
docker compose up --build
```

Verify that `.env` is ignored and that API/mailbox/model credentials are placeholders only.

## Suggested submission package

- Git repository link
- README
- `docs/WRITEUP.md`
- `docs/ARCHITECTURE.md`
- `samples/outputs/*.json`
- `samples/outputs/batch_report.csv`
- screenshots or a short screen recording

The assignment document contains the recipient emails and deadline; use the original assignment for those administrative details rather than duplicating them in the public repository.
