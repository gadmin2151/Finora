export type Currency = "MDL" | "EUR" | "USD" | "RON";
export type Organization = { id: string; name: string; role: "admin" | "user" };
export type User = {
  id: string;
  username: string;
  name: string;
  csrf: string;
  is_server_admin?: boolean;
  avatar_url?: string | null;
  organizations: Organization[];
};
export type Account = {
  id: string;
  name: string;
  currency: Currency;
  kind: string;
  opening_minor: number;
  balance_minor: number;
  archived: boolean;
  color: string;
};
export type Category = {
  id: string;
  name: string;
  icon: string;
  color: string;
  parent_id?: string | null;
  spent_minor?: number;
  previous_minor?: number;
  budget_minor?: number | null;
  budget_id?: string | null;
};
export type Transaction = {
  id: string;
  kind: string;
  amount_minor: number;
  currency: Currency;
  base_minor: number;
  account_id: string;
  target_account_id: string | null;
  target_minor: number | null;
  category_id: string | null;
  merchant: string;
  note: string;
  occurred_on: string;
  fx_rate: string;
  version: number;
  receipt_id: string | null;
  debt_id: string | null;
  occurrence_id: string | null;
  refund_of: string | null;
  splits: { category_id: string | null; amount_minor: number }[];
};
export type Debt = {
  id: string;
  person: string;
  direction: "lent" | "borrowed";
  currency: Currency;
  remaining_minor: number;
  initial_minor: number;
  due_date: string | null;
  note: string;
};
export type Bill = {
  id: string;
  bill_id: string;
  name: string;
  amount_minor: number;
  base_minor: number;
  currency: Currency;
  fx_rate: string;
  category_id: string | null;
  account_id: string | null;
  due_date: string;
  recurrence: string;
  active: boolean;
  transaction_id: string | null;
  status: "paid" | "skipped" | "overdue" | "upcoming";
};
export type BillTemplate = Omit<
  Bill,
  "status" | "due_date" | "bill_id" | "transaction_id" | "base_minor"
> & { start_date: string };
export type Dashboard = {
  month: string;
  income_minor: number;
  expense_minor: number;
  net_minor: number;
  previous_expense_minor: number;
  categories: Category[];
  chart: { day: number; expense: number; income: number }[];
  accounts: Account[];
  balances: Record<string, number>;
  bills: Bill[];
  debts: Debt[];
  planned_remaining_minor: number;
  available_mdl_minor: number;
  comparison_label: string;
  as_of: string;
};
export type ReceiptItem = {
  id: string;
  name: string;
  quantity: string;
  unit: string;
  unit_price_minor: number;
  total_minor: number;
  category_id: string | null;
};
export type Receipt = {
  id: string;
  source: string;
  source_url: string | null;
  merchant: string;
  purchased_on: string | null;
  currency: Currency;
  total_minor: number | null;
  status: string;
  error: string | null;
  warnings: string[];
  account_id: string | null;
  fx_rate: string;
  version: number;
  transaction_id: string | null;
  files: string[];
  created_at: string;
  items: ReceiptItem[];
};
export type Preferences = {
  provider: "disabled" | "ollama" | "openai";
  model: string;
  vision_model: string;
  has_openai_key: boolean;
  monthly_request_limit: number;
  auto_post: boolean;
  default_account_id: string | null;
  usage: { requests: number; input_tokens: number; output_tokens: number };
  secure_cookies: boolean;
};
export type Message = {
  id: string;
  role: string;
  text: string;
  receipt_id: string | null;
  created_at: string;
  details: {
    provider?: string;
    error?: boolean;
    month?: string;
    reports?: AnalyticsReport[];
    job_id?: string;
  };
};
export type ReportKind =
  "summary" | "categories" | "merchants" | "trend" | "purchases" | "prices";
export type AnalyticsReport = {
  query: {
    kind: ReportKind;
    date_from: string;
    date_to: string;
    search: string;
    merchant: string;
    category_id: string;
    currency: string | null;
  };
  title: string;
  metrics: { label: string; value: string }[];
  rows: {
    label: string;
    value: string;
    detail: string;
    receipt_id: string | null;
  }[];
  total_rows: number;
  notices: string[];
};
export type Job = {
  id: string;
  kind: string;
  status: string;
  progress: string;
  payload: { model?: string };
  result: { provider?: string; answer?: string };
  created_at: string;
};
export type Insight = {
  id: string;
  kind: string;
  title: string;
  text: string;
  saving_minor: number;
  category_id?: string;
  basis: string;
};
export type Rule = {
  id: string;
  pattern: string;
  field: string;
  category_id: string;
};
export type Route =
  | "users"
  | "income"
  | "purchases"
  | "organizations"
  | "overview"
  | "transactions"
  | "assistant"
  | "receipts"
  | "budgets"
  | "bills"
  | "debts"
  | "insights"
  | "reports"
  | "accounts"
  | "settings";
