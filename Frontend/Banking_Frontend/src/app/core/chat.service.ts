import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ChatResponse } from './models';

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly baseUrl = `${environment.apiBaseUrl}/chat`;

  constructor(private readonly http: HttpClient) {}

  ask(message: string, topK?: number): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.baseUrl}/ask`, { message, topK });
  }

  health(): Observable<unknown> {
    return this.http.get(`${environment.apiBaseUrl}/health`);
  }
}
