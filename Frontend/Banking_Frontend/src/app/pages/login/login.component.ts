import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-login',
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <section class="auth-card">
      <h2>{{ mode() === 'login' ? 'Sign in' : 'Create your account' }}</h2>
      <p class="muted">Access your accounts and the Secure Bank policy assistant.</p>

      <form [formGroup]="form" (ngSubmit)="submit()">
        <label>
          Username
          <input formControlName="username" autocomplete="username" placeholder="alice" />
        </label>
        <label>
          Password
          <input formControlName="password" type="password" autocomplete="current-password"
                 placeholder="password123" />
        </label>

        @if (error()) {
          <div class="alert">{{ error() }}</div>
        }

        <button class="btn primary" type="submit" [disabled]="form.invalid || loading()">
          {{ loading() ? 'Please wait…' : (mode() === 'login' ? 'Sign in' : 'Register') }}
        </button>
      </form>

      <button class="btn link" type="button" (click)="toggleMode()">
        {{ mode() === 'login' ? 'Need an account? Register' : 'Already registered? Sign in' }}
      </button>

      <div class="hint">
        <strong>Demo credentials</strong>
        <span>alice / password123 &nbsp;·&nbsp; admin / admin123</span>
      </div>
    </section>
  `
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly mode = signal<'login' | 'register'>('login');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.minLength(3)]],
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  toggleMode(): void {
    this.mode.set(this.mode() === 'login' ? 'register' : 'login');
    this.error.set(null);
  }

  submit(): void {
    if (this.form.invalid) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    const { username, password } = this.form.getRawValue();
    const request$ = this.mode() === 'login'
      ? this.auth.login(username, password)
      : this.auth.register(username, password);

    request$.subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigate(['/']);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'Authentication failed. Please try again.');
      }
    });
  }
}
