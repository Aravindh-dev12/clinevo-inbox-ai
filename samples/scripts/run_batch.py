#!/usr/bin/env python3
"""Run the generated synthetic PDF corpus against the local AI service and capture JSON + timings."""
from __future__ import annotations
import argparse, csv, json, re, time
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parents[1]
MANIFEST=json.loads((ROOT/'expected'/'manifest.json').read_text(encoding='utf-8'))

def _sentence_count(value):
    if not isinstance(value,str):
        return 0
    return len([sentence for sentence in re.split(r'(?<=[.!?])\s+',value.strip()) if sentence.strip()])

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--url',default='http://localhost:8000')
    p.add_argument('--out',default=str(ROOT/'outputs'))
    p.add_argument('--require-exact',action='store_true',help='exit non-zero when any fixture classification differs from the manifest or any PDF summary is outside 10-15 sentences')
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
        summary_sentences=_sentence_count(payload.get('summary',''))
        summary_length_ok=10 <= summary_sentences <= 15
        rows.append({'file':pdf.name,'kind':item['kind'],'expected':'|'.join(item['expected']),'predicted':'|'.join(predicted),'elapsed_ms_client':elapsed,'elapsed_ms_service':payload.get('processing_ms',''),'match':set(predicted)==set(item['expected']),'summary_sentences':summary_sentences,'summary_length_ok':summary_length_ok})
        print(f'{pdf.name}: {elapsed} ms -> {predicted}; summary={summary_sentences} sentences')
    with (out/'batch_report.csv').open('w',newline='',encoding='utf-8') as h:
        w=csv.DictWriter(h,fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
    exact_matches=sum(1 for x in rows if x['match'])
    summary_length_matches=sum(1 for x in rows if x['summary_length_ok'])
    summary={'documents':len(rows),'classification_exact_matches':exact_matches,'classification_exact_rate':round(exact_matches/len(rows),4),'summary_length_matches':summary_length_matches,'summary_length_rate':round(summary_length_matches/len(rows),4),'summary_requirement':'10-15 sentences per PDF','mean_client_ms':round(sum(x['elapsed_ms_client'] for x in rows)/len(rows),1),'max_client_ms':max(x['elapsed_ms_client'] for x in rows),'note':'Synthetic data only.'}
    (out/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8'); print(json.dumps(summary,indent=2))
    if a.require_exact and (exact_matches != len(rows) or summary_length_matches != len(rows)):
        classification_failures=', '.join(row['file'] for row in rows if not row['match']) or 'none'
        summary_failures=', '.join(row['file'] for row in rows if not row['summary_length_ok']) or 'none'
        raise SystemExit(f'acceptance regression: classifications {exact_matches}/{len(rows)} exact (mismatches: {classification_failures}); summaries {summary_length_matches}/{len(rows)} within 10-15 sentences (mismatches: {summary_failures})')
if __name__=='__main__': main()
