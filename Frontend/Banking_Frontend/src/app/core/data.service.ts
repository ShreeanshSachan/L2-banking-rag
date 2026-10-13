import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { BalanceResponse, CardView, LoanView } from './models';

@Injectable({ providedIn: 'root' })
export class DataService {
  private readonly baseUrl = `${environment.apiBaseUrl}/api/me`;

  constructor(private readonly http: HttpClient) {}

  getBalance(): Observable<BalanceResponse> {
    return this.http.get<BalanceResponse>(`${this.baseUrl}/balance`);
  }

  getCards(): Observable<CardView[]> {
    return this.http.get<CardView[]>(`${this.baseUrl}/cards`);
  }

  getLoans(): Observable<LoanView[]> {
    return this.http.get<LoanView[]>(`${this.baseUrl}/loans`);
  }
}
