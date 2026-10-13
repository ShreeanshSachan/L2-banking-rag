import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ChatService } from '../../core/chat.service';
import { ChatMessage } from '../../core/models';

@Component({
  selector: 'app-chat',
  imports: [CommonModule],
  template: `
    <div class="chat-layout">
      <div class="chat-window">
        @if (messages().length === 0) {
          <div class="empty">
            <h3>How can I help?</h3>
            <p class="muted">
              Ask about Secure Bank policies (loans, KYC, cards, deposits) or your own
              accounts. Answers are grounded in official documents.
            </p>
            <div class="suggestions">
              @for (s of suggestions; track s) {
                <button class="chip" (click)="send(s)">{{ s }}</button>
              }
            </div>
          </div>
        }

        @for (msg of messages(); track $index) {
          <div class="bubble" [class.user]="msg.role === 'user'" [class.assistant]="msg.role === 'assistant'">
            <div class="meta">
              <span>{{ msg.role === 'user' ? 'You' : 'Assistant' }}</span>
              @if (msg.role === 'assistant' && msg.intent) {
                <span class="tag" [class]="msg.intent">{{ msg.intent }}</span>
                @if (msg.latencyMs) { <span class="muted">{{ msg.latencyMs }} ms</span> }
                @if (msg.grounded === false) { <span class="tag warn">unverified</span> }
              }
            </div>
            <p class="text">{{ msg.text }}</p>

            @if (msg.citations && msg.citations.length > 0) {
              <details class="citations">
                <summary>{{ msg.citations.length }} source(s)</summary>
                @for (c of msg.citations; track $index) {
                  <div class="citation">
                    <div class="citation-head">{{ c.source }} · page {{ c.page }} · score {{ c.score }}</div>
                    <div class="muted">{{ c.snippet }}</div>
                  </div>
                }
              </details>
            }
          </div>
        }

        @if (loading()) {
          <div class="bubble assistant"><p class="text muted">Thinking…</p></div>
        }
      </div>

      @if (error()) {
        <div class="alert">{{ error() }}</div>
      }

      <div class="composer">
        <textarea #box rows="1" [value]="input()" placeholder="Ask a question…"
                  (input)="input.set(box.value)" (keydown)="onKeydown($event, box)"></textarea>
        <button class="btn primary" [disabled]="loading() || input().trim().length === 0" (click)="send()">
          Send
        </button>
      </div>
    </div>
  `
})
export class ChatComponent {
  private readonly chatService = inject(ChatService);

  readonly messages = signal<ChatMessage[]>([]);
  readonly input = signal('');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly suggestions = [
    'What is the KYC checklist for onboarding?',
    'What are the FD interest rates for 1–2 years?',
    'What is my account balance?',
    'What is my credit card outstanding?',
    'What is the chargeback timeline for cards?',
    'Increase my credit limit now'
  ];

  send(text?: string): void {
    const message = (text ?? this.input()).trim();
    if (!message || this.loading()) {
      return;
    }
    this.messages.update((m) => [...m, { role: 'user', text: message }]);
    this.input.set('');
    this.loading.set(true);
    this.error.set(null);

    this.chatService.ask(message).subscribe({
      next: (res) => {
        this.loading.set(false);
        this.messages.update((m) => [
          ...m,
          {
            role: 'assistant',
            text: res.answer,
            intent: res.intent,
            citations: res.citations,
            latencyMs: res.latencyMs,
            grounded: res.grounded
          }
        ]);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'The assistant is unavailable right now.');
      }
    });
  }

  onKeydown(event: KeyboardEvent, box: HTMLTextAreaElement): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
      box.value = '';
    }
  }
}
