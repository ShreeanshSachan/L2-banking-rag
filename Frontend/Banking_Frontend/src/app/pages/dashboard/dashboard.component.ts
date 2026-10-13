import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { DataService } from '../../core/data.service';
import { AccountView, CardView, LoanView } from '../../core/models';

@Component({
  selector: 'app-dashboard',
  imports: [CommonModule, RouterLink],
  template: `
    <div class="page-header">
      <h2>Your accounts</h2>
      <a class="btn primary" routerLink="/chat">Ask the assistant</a>
    </div>

    @if (error()) {
      <div class="alert">{{ error() }}</div>
    }

    <div class="grid">
      <section class="panel">
        <h3>Balances</h3>
        @if (accounts().length === 0) {
          <p class="muted">No accounts found.</p>
        }
        @for (acc of accounts(); track acc.accountNumber) {
          <div class="row">
            <span>{{ acc.accountNumber }} ({{ acc.currency }})</span>
            <strong>{{ acc.balance | currency: acc.currency }}</strong>
          </div>
        }
      </section>

      <section class="panel">
        <h3>Cards</h3>
        @if (cards().length === 0) {
          <p class="muted">No cards found.</p>
        }
        @for (card of cards(); track card.id) {
          <div class="row">
            <span>{{ card.type }} •••• {{ card.last4 }} <em>({{ card.status }})</em></span>
            <span><strong>{{ card.outstanding | currency:'INR' }}</strong> / {{ card.creditLimit | currency:'INR' }}</span>
          </div>
        }
      </section>

      <section class="panel">
        <h3>Loans</h3>
        @if (loans().length === 0) {
          <p class="muted">No loans found.</p>
        }
        @for (loan of loans(); track loan.id) {
          <div class="row">
            <span>{{ loan.productType }} · {{ loan.interestRate }}% p.a.</span>
            <span><strong>{{ loan.outstanding | currency:'INR' }}</strong> outstanding</span>
          </div>
        }
      </section>
    </div>
  `
})
export class DashboardComponent implements OnInit {
  private readonly dataService = inject(DataService);

  readonly accounts = signal<AccountView[]>([]);
  readonly cards = signal<CardView[]>([]);
  readonly loans = signal<LoanView[]>([]);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    forkJoin({
      balance: this.dataService.getBalance(),
      cards: this.dataService.getCards(),
      loans: this.dataService.getLoans()
    }).subscribe({
      next: ({ balance, cards, loans }) => {
        this.accounts.set(balance.accounts);
        this.cards.set(cards);
        this.loans.set(loans);
      },
      error: () => this.error.set('Could not load your account data. Is the backend running?')
    });
  }
}
