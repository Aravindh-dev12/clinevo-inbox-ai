import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { ApiService } from './api.service';
import { LiteratureBatchResult, LiteratureCaseResult } from './models';

interface FactRow {
  group: string;
  field: string;
  value: string;
  confidence: number;
  source: string;
}

@Component({
  selector: 'app-literature-screening',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="literature-wrap">
      <div class="card literature-card">
        <div class="panel-heading">
          <div><div class="eyebrow">OPTIONAL BONUS · LITERATURE SCREENING</div><h3>Independent article PDF screening</h3></div>
          <span *ngIf="result">{{ result.total_documents }} docs · {{ result.total_cases }} case segments · {{ result.processing_ms }} ms</span>
        </div>
        <p class="muted">Upload synthetic article PDFs independently of the mailbox. The service splits numbered cases, screens each case against the four minimum ICSR elements, and retains page provenance.</p>
        <div class="literature-actions">
          <label class="file-picker">Choose article PDFs<input type="file" accept="application/pdf,.pdf" multiple (change)="selectFiles($event)" /></label>
          <span class="muted">{{ files.length ? files.length + ' selected' : 'No files selected' }}</span>
          <button class="primary" [disabled]="loading || files.length === 0" (click)="screen()">{{ loading ? 'Screening…' : 'Screen batch' }}</button>
        </div>
        <div class="error literature-error" *ngIf="error">{{ error }}</div>
      </div>

      <div class="literature-grid" *ngIf="result as batch">
        <section class="card" *ngFor="let document of batch.documents">
          <div class="panel-heading">
            <h3>{{ document.file_name }}</h3>
            <span>{{ document.pdf_type }} · {{ document.detected_language }} · {{ document.processing_ms }} ms</span>
          </div>
          <div class="case-card" *ngFor="let caseItem of document.cases">
            <div class="case-meta">
              <strong>{{ caseItem.case_id }}</strong>
              <span class="status" [class.reportable]="caseItem.reportable">{{ caseItem.reportable ? 'REPORTABLE CANDIDATE' : 'NOT REPORTABLE' }}</span>
              <span>{{ confidence(caseItem.confidence) }}</span>
              <span>pages {{ caseItem.source_pages.join(', ') || '—' }}</span>
            </div>
            <p><strong>Why:</strong> {{ caseItem.relevance_reason }}</p>
            <p class="summary">{{ caseItem.summary }}</p>
            <div class="chips">
              <div class="classification" *ngFor="let classification of caseItem.classifications">
                <div class="classification-top"><strong>{{ classification.category }}</strong><span>{{ confidence(classification.confidence) }}</span></div>
                <div class="meter"><span [style.width.%]="classification.confidence * 100"></span></div>
                <p>{{ classification.reason }}</p>
              </div>
            </div>
            <details *ngIf="caseItem.reportable">
              <summary>Structured facts with source provenance</summary>
              <div class="facts-list">
                <div class="literature-fact" *ngFor="let row of factRows(caseItem)">
                  <span><strong>{{ row.group }}</strong> / {{ row.field }}</span>
                  <span>{{ row.value }}</span>
                  <span>{{ confidence(row.confidence) }}</span>
                  <span>{{ row.source }}</span>
                </div>
              </div>
            </details>
          </div>
          <div class="muted" *ngIf="document.cases.length === 0">No case-like content was extracted from this document.</div>
        </section>
      </div>
    </section>
  `
})
export class LiteratureScreeningComponent {
  files: File[] = [];
  result?: LiteratureBatchResult;
  loading = false;
  error = '';

  constructor(private readonly api: ApiService) {}

  selectFiles(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.files = Array.from(input.files ?? []).slice(0, 25);
    this.result = undefined;
    this.error = '';
  }

  screen(): void {
    if (this.files.length === 0) return;
    this.loading = true;
    this.error = '';
    this.api.screenLiterature(this.files).subscribe({
      next: result => {
        this.result = result;
        this.loading = false;
      },
      error: () => {
        this.error = 'Literature screening failed. Verify the backend and AI service are healthy and that only synthetic PDFs are being used.';
        this.loading = false;
      }
    });
  }

  confidence(value?: number): string {
    return value == null ? '—' : `${Math.round(value * 100)}%`;
  }

  factRows(caseItem: LiteratureCaseResult): FactRow[] {
    const rows: FactRow[] = [];
    for (const [group, fields] of Object.entries(caseItem.extracted_facts ?? {})) {
      for (const [field, extracted] of Object.entries(fields)) {
        if (!extracted || extracted.value === 'Not stated') continue;
        const source = extracted.source;
        rows.push({
          group,
          field,
          value: extracted.value,
          confidence: extracted.confidence,
          source: source?.page ? `${source.source_name} · page ${source.page}` : (source?.source_name ?? 'unknown')
        });
      }
    }
    return rows;
  }
}
