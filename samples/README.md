# Synthetic test corpus

Everything under `samples/` is invented for this candidate assignment. **No real patient, reporter, client, or production mailbox data is permitted.**

## Coverage

The generator creates:

- 15 synthetic emails covering ICSR, PQC, MI, multi-label and irrelevant cases
- 5 normal digital PDFs
- 2 image-only scanned/handwritten-style PDFs
- 5 fictional two-column article PDFs
- 2 non-English PDFs (Spanish and French)
- tables in digital safety examples
- combined ICSR + PQC examples

`expected/*.json` stores regression expectations, not model-generated claims. `scripts/run_batch.py` writes the actual JSON and per-document processing times to `samples/outputs/` for the exact model/configuration used in the walkthrough.

## Generate the files

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r samples/requirements.txt
python samples/scripts/generate_corpus.py
```

The generated binary PDFs are intentionally not hand-maintained in git; the deterministic generator is the source of truth.

## Run the AI batch

```bash
python samples/scripts/run_batch.py --url http://localhost:8000
```

## Send the email fixtures to a test mailbox

```bash
python samples/scripts/send_samples.py \
  --host smtp.example.test --port 587 --starttls \
  --username "$SMTP_USER" --password "$SMTP_PASSWORD" \
  --to "$TEST_MAILBOX"
```

Use only an isolated test mailbox and synthetic fixtures.
