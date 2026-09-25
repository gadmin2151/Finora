from datetime import date
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

Currency = Literal["MDL", "EUR", "USD", "RON"]
Money = Decimal


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    @field_validator("occurred_on", "due_date", "start_date", "purchased_on", check_fields=False)
    @classmethod
    def date_window(cls, value: date | None):
        if value is not None and not 1990 <= value.year <= 2100:
            raise ValueError("Дата должна быть между 1990 и 2100 годом")
        return value


class Login(Strict):
    username: str = Field(min_length=1, max_length=80)
    password: str = Field(min_length=1, max_length=256)


class PasswordChange(Strict):
    current_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=12, max_length=256)


class OrganizationInput(Strict):
    name: str = Field(min_length=1, max_length=100)


class MemberInput(Strict):
    username: str = Field(min_length=3, max_length=80, pattern=r"^[a-zA-Z0-9_.@-]+$")
    name: str = Field(default="", max_length=100)
    password: str | None = Field(default=None, min_length=12, max_length=256)
    role: Literal["admin", "user"] = "user"


class MemberRole(Strict):
    role: Literal["admin", "user"]


class UserMembership(Strict):
    organization_id: str = Field(min_length=1, max_length=36)
    role: Literal["admin", "user"] = "user"


class UserEdit(Strict):
    name: str = Field(min_length=1, max_length=100)
    is_active: bool = True
    memberships: list[UserMembership] = Field(default_factory=list, max_length=100)


class UserCreate(UserEdit):
    username: str = Field(min_length=3, max_length=80, pattern=r"^[a-zA-Z0-9_.@-]+$")
    password: str = Field(min_length=12, max_length=256)


class PasswordReset(Strict):
    password: str = Field(min_length=12, max_length=256)


class ProfileEdit(Strict):
    name: str = Field(min_length=1, max_length=100)


class CommentInput(Strict):
    text: str = Field(min_length=1, max_length=3000)


class AccountInput(Strict):
    name: str = Field(min_length=1, max_length=100)
    currency: Currency = "MDL"
    kind: Literal["cash", "card", "savings"] = "card"
    opening_balance: Money = Field(default=Decimal(0), ge=-1000000000, le=1000000000)
    color: str = Field(default="#16a69b", pattern=r"^#[0-9a-fA-F]{6}$")


class CategoryInput(Strict):
    name: str = Field(min_length=1, max_length=100)
    color: str = Field(default="#16a69b", pattern=r"^#[0-9a-fA-F]{6}$")
    icon: str = Field(default="tag", max_length=32)
    parent_id: str | None = None


class SplitInput(Strict):
    category_id: str | None = None
    amount: Money = Field(gt=0, le=1000000000)


class TransactionInput(Strict):
    kind: Literal["expense", "income", "transfer", "refund", "adjustment"] = "expense"
    amount: Money = Field(gt=0, le=1000000000)
    account_id: str
    occurred_on: date
    fx_rate: Decimal | None = Field(default=None, gt=0, le=100000)
    target_account_id: str | None = None
    target_amount: Money | None = Field(default=None, gt=0, le=1000000000)
    category_id: str | None = None
    merchant: str = Field(default="", max_length=200)
    note: str = Field(default="", max_length=3000)
    refund_of: str | None = None
    splits: list[SplitInput] = Field(default_factory=list, max_length=200)
    idempotency_key: str = Field(min_length=8, max_length=100)

    @field_validator("occurred_on")
    @classmethod
    def valid_date(cls, value: date) -> date:
        if value.year < 1990 or value.year > 2100:
            raise ValueError("Дата должна быть между 1990 и 2100 годом")
        return value


class TransactionEdit(TransactionInput):
    version: int = Field(ge=1)


class DebtInput(Strict):
    person: str = Field(min_length=1, max_length=100)
    direction: Literal["lent", "borrowed"]
    currency: Currency = "MDL"
    amount: Money = Field(gt=0, le=1000000000)
    mode: Literal["existing", "new"] = "existing"
    account_id: str | None = None
    occurred_on: date
    due_date: date | None = None
    fx_rate: Decimal | None = Field(default=None, gt=0, le=100000)
    note: str = Field(default="", max_length=3000)
    idempotency_key: str = Field(min_length=8, max_length=100)


class DebtPayment(Strict):
    amount: Money = Field(gt=0, le=1000000000)
    account_id: str
    occurred_on: date
    fx_rate: Decimal | None = Field(default=None, gt=0, le=100000)
    idempotency_key: str = Field(min_length=8, max_length=100)


class BillInput(Strict):
    name: str = Field(min_length=1, max_length=100)
    amount: Money = Field(gt=0, le=1000000000)
    currency: Currency = "MDL"
    fx_rate: Decimal | None = Field(default=None, gt=0, le=100000)
    category_id: str | None = None
    account_id: str | None = None
    start_date: date
    recurrence: Literal["monthly", "quarterly", "yearly", "weekly", "once"] = "monthly"


class BillPayment(Strict):
    account_id: str
    occurred_on: date
    amount: Money = Field(gt=0, le=1000000000)
    fx_rate: Decimal | None = Field(default=None, gt=0, le=100000)
    transaction_id: str | None = None
    idempotency_key: str = Field(min_length=8, max_length=100)


class IncomePlanInput(BillInput):
    idempotency_key: str = Field(min_length=8, max_length=100)
    version: int | None = Field(default=None, ge=1)


class IncomeActive(Strict):
    active: bool
    version: int = Field(ge=1)


class BudgetInput(Strict):
    month: str = Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")
    category_id: str
    amount: Money = Field(gt=0, le=1000000000)


class RuleInput(Strict):
    pattern: str = Field(min_length=2, max_length=150)
    field: Literal["item", "merchant"] = "item"
    category_id: str


class PreferencesInput(Strict):
    provider: Literal["disabled", "ollama", "openai"]
    model: str = Field(min_length=1, max_length=100, pattern=r"^[a-zA-Z0-9_.:/-]+$")
    vision_model: str = Field(min_length=1, max_length=100, pattern=r"^[a-zA-Z0-9_.:/-]+$")
    openai_key: str | None = Field(default=None, max_length=512)
    clear_key: bool = False
    monthly_request_limit: int = Field(default=200, ge=1, le=10000)
    auto_post: bool = True
    default_account_id: str | None = None


class ChatInput(Strict):
    text: str = Field(min_length=1, max_length=3000)
    month: str = Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")
    report: Literal["summary", "categories", "merchants", "trend", "prices"] | None = None
    request_key: str | None = Field(default=None, min_length=16, max_length=100)


class ReceiptLineInput(Strict):
    name: str = Field(min_length=1, max_length=300)
    quantity: Decimal = Field(gt=0, le=100000)
    unit: str = Field(default="шт", max_length=12)
    unit_price: Money = Field(ge=0, le=1000000000)
    total: Money = Field(ge=0, le=1000000000)
    category_id: str | None = None


class ReceiptConfirm(Strict):
    merchant: str = Field(min_length=1, max_length=200)
    purchased_on: date
    currency: Currency = "MDL"
    total: Money = Field(gt=0, le=1000000000)
    account_id: str
    fx_rate: Decimal | None = Field(default=None, gt=0, le=100000)
    items: list[ReceiptLineInput] = Field(min_length=1, max_length=200)
    version: int = Field(ge=1)
    transaction_id: str | None = None


class ReceiptLink(Strict):
    url: str = Field(min_length=10, max_length=1000)
    account_id: str | None = None
    fx_rate: Decimal | None = Field(default=None, gt=0, le=100000)
    review_required: bool = False


class ReceiptAccept(Strict):
    version: int = Field(ge=1)
    account_id: str


class ReceiptReview(ReceiptConfirm):
    # Members can correct their new receipt, but cannot modify an existing expense.
    transaction_id: Literal[None] = None


class ModelPull(Strict):
    model: Literal["qwen3:0.6b", "qwen3:4b-instruct", "qwen3:4b", "gemma3:4b", "qwen2.5:1.5b"]
