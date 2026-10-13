export interface AuthResponse {
  token: string;
  tokenType: string;
  username: string;
  userId: number;
  roles: string;
}

export interface Citation {
  source: string;
  page: number;
  score: number;
  snippet: string;
}

export interface ChatResponse {
  answer: string;
  intent: string;
  citations: Citation[];
  latencyMs: number;
  masked: boolean;
  grounded: boolean;
}

export interface AccountView {
  accountNumber: string;
  currency: string;
  balance: number;
}

export interface BalanceResponse {
  accounts: AccountView[];
}

export interface CardView {
  id: number;
  type: string;
  last4: string;
  creditLimit: number;
  outstanding: number;
  status: string;
}

export interface LoanView {
  id: number;
  productType: string;
  principal: number;
  interestRate: number;
  outstanding: number;
  status: string;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  intent?: string;
  citations?: Citation[];
  latencyMs?: number;
  grounded?: boolean;
}
