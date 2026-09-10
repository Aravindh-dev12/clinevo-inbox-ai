import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from './api.service';
import { LiteratureScreeningComponent } from './literature-screening.component';
import { Classification, FactOverride, InboxDetail, InboxQueueItem } from './models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, LiteratureScreeningComponent],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {
  items: InboxQueueItem[] = [];
  selected?: InboxDetail;
  selectedId?: number;
  loading = false;
  error = '';
  reviewer = 'candidate-reviewer';
  overrideCategories: Array<Classification['category']> = [];
  readonly classificationOptions: Array<Classification['category']> = ['ICSR', 'PQC', 'MI', 'NOT_RELEVANT'];
  note = '';
  private readonly originalValues = new Map<number, string>();

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.api.listInbox().subscribe({
      next: items => {
        this.items = [...items].sort((a, b) => {
          const priority = (b.reviewPriorityScore ?? 0) - (a.reviewPriorityScore ?? 0);
          return priority !== 0 ? priority : b.receivedAt.localeCompare(a.receivedAt);
        });
        this.loading = false;
        if (!this.selectedId && this.items.length > 0) this.open(this.items[0].id);
      },
      error: () => {
        this.error = 'Could not load inbox. Verify the Spring Boot API is running.';
        this.loading = false;
      }
    });
  }

  open(id: number): void {
    this.selectedId = id;
    this.error = '';
    this.api.getDetail(id).subscribe({
      next: detail => {
        this.selected = detail;
        this.originalValues.clear();
        for (const fact of detail.facts) this.originalValues.set(fact.id, fact.fieldValue ?? '');
        this.overrideCategories = detail.classifications.map(item => item.category);
        this.note = '';
      },
      error: () => this.error = 'Could not load item detail.'
    });
  }

  confidence(value?: number): string {
    return value == null ? '—' : `${Math.round(value * 100)}%`;
  }

  queueSummary(value?: string): string {
    if (!value) return 'Summary pending.';
    const normalized = value.replace(/\s+/g, ' ').trim();
    return normalized.length <= 150 ? normalized : `${normalized.slice(0, 147)}…`;
  }

  sourceLabel(page?: number, name?: string): string {
    return page ? `${name ?? 'PDF'} · page ${page}` : (name ?? 'Email');
  }

  attachmentUrl(messageId: number, attachmentId: number): string {
    return this.api.attachmentUrl(messageId, attachmentId);
  }

  isOverrideCategorySelected(category: Classification['category']): boolean {
    return this.overrideCategories.includes(category);
  }

  toggleOverrideCategory(category: Classification['category'], checked: boolean): void {
    if (checked) {
      if (category === 'NOT_RELEVANT') {
        this.overrideCategories = ['NOT_RELEVANT'];
        return;
      }
      this.overrideCategories = this.overrideCategories.filter(item => item !== 'NOT_RELEVANT');
      if (!this.overrideCategories.includes(category)) this.overrideCategories = [...this.overrideCategories, category];
      return;
    }
    this.overrideCategories = this.overrideCategories.filter(item => item !== category);
  }

  submit(action: 'ACCEPT' | 'OVERRIDE'): void {
    if (!this.selected || !this.reviewer.trim()) return;
    const factOverrides: FactOverride[] = this.selected.facts
      .filter(f => (f.fieldValue ?? '') !== (this.originalValues.get(f.id) ?? ''))
      .map(f => ({ factId: f.id, newValue: f.fieldValue ?? '' }));

    if (action === 'OVERRIDE' && this.overrideCategories.length === 0 && factOverrides.length === 0) {
      this.error = 'Select at least one classification or edit a field before overriding.';
      return;
    }

    this.api.review(this.selected.message.id, {
      action,
      overrideCategories: action === 'OVERRIDE' && this.overrideCategories.length > 0 ? this.overrideCategories : undefined,
      note: this.note || undefined,
      reviewer: this.reviewer.trim(),
      factOverrides
    }).subscribe({
      next: () => {
        this.refresh();
        this.open(this.selected!.message.id);
      },
      error: () => this.error = 'Review action failed. Check the API logs.'
    });
  }
}
