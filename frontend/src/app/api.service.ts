import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { InboxDetail, InboxMessage, ReviewRequest } from './models';

declare global {
  interface Window {
    __CLINEVO_CONFIG__?: { apiBaseUrl?: string };
  }
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly baseUrl = window.__CLINEVO_CONFIG__?.apiBaseUrl ?? 'http://localhost:8080/api';

  constructor(private readonly http: HttpClient) {}

  listInbox(): Observable<InboxMessage[]> {
    return this.http.get<InboxMessage[]>(`${this.baseUrl}/inbox`);
  }

  getDetail(id: number): Observable<InboxDetail> {
    return this.http.get<InboxDetail>(`${this.baseUrl}/inbox/${id}/detail`);
  }

  review(id: number, request: ReviewRequest): Observable<InboxMessage> {
    return this.http.post<InboxMessage>(`${this.baseUrl}/inbox/${id}/review`, request);
  }
}
