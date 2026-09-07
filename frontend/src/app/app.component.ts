import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from './api.service';
import { FactOverride, InboxDetail, InboxMessage } from './models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {
  items: InboxMessage[] = [];
  selected?: InboxDetail;
  selectedId?: number;
  loading = false;
  error = '';
  reviewer = 'candidate-reviewer';
  overrideCategory = '';
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
        this.items = [...items].sort((a, b) => b.receivedAt.localeCompare(a.receivedAt));
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
        this.overrideCategory = '';
        this.note = '';
      },
      error: () => this.error = 'Could not load item detail.'
    });
  }

  confidence(value?: number): string {
    return value == null ? '—' : `${Math.round(value * 100)}%`;
  }

  sourceLabel(page?: number, name?: string): string {
    return page ? `${name ?? 'PDF'} · page ${page}` : (name ?? 'Email');
  }

  submit(action: 'ACCEPT' | 'OVERRIDE'): void {
    if (!this.selected || !this.reviewer.trim()) return;
    const factOverrides: FactOverride[] = this.selected.facts
      .filter(f => (f.fieldValue ?? '') !== (this.originalValues.get(f.id) ?? ''))
      .map(f => ({ factId: f.id, newValue: f.fieldValue ?? '' }));

    this.api.review(this.selected.message.id, {
      action,
      overrideCategory: action === 'OVERRIDE' ? this.overrideCategory || undefined : undefined,
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
