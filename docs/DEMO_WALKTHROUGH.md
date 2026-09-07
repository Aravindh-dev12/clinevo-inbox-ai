# 15–20 minute live walkthrough

## 0–2 min — problem and architecture

Open `docs/ARCHITECTURE.md` and explain the flow: test mailbox → Spring Boot → asynchronous AI document service → Oracle → Angular human review. Emphasize that AI performs a first pass and the reviewer remains the final authority.

## 2–4 min — show the running stack

```bash
docker compose ps
curl http://localhost:8080/actuator/health
curl http://localhost:8000/health
```

Show that secrets come from `.env`, not source control.

## 4–9 min — inbox workflow

Open the Angular UI.

1. Select a synthetic ICSR message.
2. Point out sender/subject/date/body.
3. Show PDF type/language/OCR confidence/processing milliseconds.
4. Show multi-label classification, confidence and one-line reason.
5. Show patient/reporter/product/reaction/severity facts.
6. Open source provenance and demonstrate PDF page/evidence.
7. Show a missing field represented as `Not stated` / absent rather than guessed.
8. Accept the AI result and show the timestamped audit entry.
9. Edit a field or category on a second message, save override and show old/new audit history.

## 9–12 min — PDF edge cases

Show one generated scanned/handwritten-style PDF, one article PDF and one non-English PDF. Explain direct extraction vs OCR, reference stripping, language tagging and table/image handling.

## 12–14 min — literature screening bonus

Use the independent literature upload section. Upload the synthetic article batch, show a multi-case article splitting into cases and point out reportability confidence, relevance reason, summary and page provenance.

## 14–16 min — tests and batch evidence

Show the latest green GitHub Actions run and then:

```bash
python samples/scripts/run_batch.py --url http://localhost:8000
cat samples/outputs/summary.json
```

Open one JSON output and `batch_report.csv`.

## 16–18 min — AI safety / trade-offs

Explain:

- multi-label categories rather than forced single-label classification
- four-element ICSR minimum rule
- negation handling
- `Not stated` for unsupported values
- deterministic CI fallback vs optional structured LLM
- LLM provenance validation
- synthetic-data-only policy

## 18–20 min — production evolution

Be ready to discuss why the current bounded in-process executor is acceptable for the assignment but would become a durable broker/DB queue in production, and why the demo API key would become OIDC/RBAC. Also mention immutable object storage, malware scanning, OpenTelemetry/SIEM, calibrated evaluation and formal regulated-system validation.

## Commands to have ready

```bash
docker compose up --build
docker compose ps
curl http://localhost:8080/actuator/health
curl http://localhost:8000/health
python samples/scripts/generate_corpus.py
python samples/scripts/run_batch.py --url http://localhost:8000
```
