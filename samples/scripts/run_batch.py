#!/usr/bin/env python3
"""Run the generated synthetic PDF corpus against the local AI service and capture JSON + timings."""
from __future__ import annotations
import argparse, csv, json, time
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parents[1]
MANIFEST=json.loads((ROOT/'expected'/'manifest.json').read_text(encoding='utf-8'))

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--url',default='http://localhost:8000')
    p.add_argument('--out',default=str(ROOT/'outputs'))
    p.add_argument('--require-exact',action='store_true',help='exit non-zero when any fixture classification differs from the manifest')
    a=p.parse_args()
    out=Path(a.out); out.mkdir(parents=True,exist_ok=True); rows=[]
    for item in MANIFEST:
        pdf=ROOT/'generated'/'pdfs'/item['file']
        if not pdf.exists(): raise SystemExit(f'{pdf} missing; run generate_corpus.py first')
        started=time.perf_counter()
        with pdf.open('rb') as handle:
            r=requests.post(f"{a.url.rstrip('/')}/process",files={'file':(pdf.name,handle,'application/pdf')},data={'email_text':item.get('email_text','')},timeout=120)
        elapsed=round((time.perf_counter()-started)*1000); r.raise_for_status(); payload=r.json()
        (out/f'{pdf.stem}.json').write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding='utf-8')
        predicted=[x['category'] for x in payload.get('classifications',[])]
        rows.append({'file':pdf.name,'kind':item['kind'],'expected':'|'.join(item['expected']),'predicted':'|'.join(predicted),'elapsed_ms_client':elapsed,'elapsed_ms_service':payload.get('processing_ms',''),'match':set(predicted)==set(item['expected'])})
        print(f'{pdf.name}: {elapsed} ms -> {predicted}')
    with (out/'batch_report.csv').open('w',newline='',encoding='utf-8') as h:
        w=csv.DictWriter(h,fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
    exact_matches=sum(1 for x in rows if x['match'])
    summary={'documents':len(rows),'classification_exact_matches':exact_matches,'classification_exact_rate':round(exact_matches/len(rows),4),'mean_client_ms':round(sum(x['elapsed_ms_client'] for x in rows)/len(rows),1),'max_client_ms':max(x['elapsed_ms_client'] for x in rows),'note':'Synthetic data only.'}
    (out/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8'); print(json.dumps(summary,indent=2))
    if a.require_exact and exact_matches != len(rows):
        failures=', '.join(row['file'] for row in rows if not row['match'])
        raise SystemExit(f'classification regression: {exact_matches}/{len(rows)} exact; mismatches: {failures}')
if __name__=='__main__': main()
