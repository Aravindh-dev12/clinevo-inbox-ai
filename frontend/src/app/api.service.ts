import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { InboxDetail, InboxMessage, LiteratureBatchResult, ReviewRequest } from './models';

declare global { interface Window { __CLINEVO_CONFIG__?: { apiBaseUrl?: string; apiKey?: string }; } }

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly baseUrl = window.__CLINEVO_CONFIG__?.apiBaseUrl ?? '/api';
  constructor(private readonly http: HttpClient) {}
  listInbox(): Observable<InboxMessage[]> { return this.http.get<InboxMessage[]>(`${this.baseUrl}/inbox`); }
  getDetail(id: number): Observable<InboxDetail> { return this.http.get<InboxDetail>(`${this.baseUrl}/inbox/${id}/detail`); }
  attachmentUrl(messageId: number, attachmentId: number): string { return `${this.baseUrl}/inbox/${messageId}/attachments/${attachmentId}`; }
  review(id: number, request: ReviewRequest): Observable<InboxMessage> {
    return this.http.post<InboxMessage>(`${this.baseUrl}/inbox/${id}/review`, request, { headers: this.writeHeaders() });
  }
  screenLiterature(files: File[]): Observable<LiteratureBatchResult> {
    const form = new FormData();
    for (const file of files) form.append('files', file, file.name);
    return this.http.post<LiteratureBatchResult>(`${this.baseUrl}/literature/screen`, form, { headers: this.writeHeaders() });
  }
  private writeHeaders(): HttpHeaders | undefined {
    const apiKey = window.__CLINEVO_CONFIG__?.apiKey;
    return apiKey ? new HttpHeaders({ 'X-API-Key': apiKey }) : undefined;
  }
}
