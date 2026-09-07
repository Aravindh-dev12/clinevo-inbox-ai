import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { InboxDetail, InboxMessage, ReviewRequest } from './models';

declare global { interface Window { __CLINEVO_CONFIG__?: { apiBaseUrl?: string; apiKey?: string }; } }

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly baseUrl = window.__CLINEVO_CONFIG__?.apiBaseUrl ?? '/api';
  constructor(private readonly http: HttpClient) {}
  listInbox(): Observable<InboxMessage[]> { return this.http.get<InboxMessage[]>(`${this.baseUrl}/inbox`); }
  getDetail(id: number): Observable<InboxDetail> { return this.http.get<InboxDetail>(`${this.baseUrl}/inbox/${id}/detail`); }
  review(id: number, request: ReviewRequest): Observable<InboxMessage> {
    const apiKey = window.__CLINEVO_CONFIG__?.apiKey;
    const headers = apiKey ? new HttpHeaders({ 'X-API-Key': apiKey }) : undefined;
    return this.http.post<InboxMessage>(`${this.baseUrl}/inbox/${id}/review`, request, { headers });
  }
}
