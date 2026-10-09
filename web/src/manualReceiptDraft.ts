export type ManualReceiptLine = {
  name: string;
  quantity: string;
  unit: string;
  unit_price: string;
  total: string;
  category_id: string;
  category_selected: boolean;
};
export type ManualReceiptDraft = {
  requestKey: string;
  merchant: string;
  address: string;
  date: string;
  currency: string;
  account: string;
  rate: string;
  items: ManualReceiptLine[];
};
type DraftStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;
const prefix = "finora.manual-receipt.v1.";
export const manualReceiptDraftKey = (userId: string, organizationId: string) =>
  prefix + JSON.stringify([userId, organizationId]);

// Drafts can contain incomplete inputs. Validate their shape, never turn them
// into accepted financial values; the form and server still validate posting.
export function parseManualReceiptDraft(
  raw: string | null,
): ManualReceiptDraft | null {
  if (!raw || raw.length > 300_000) return null;
  try {
    const draft = JSON.parse(raw);
    const text = (value: unknown, limit: number) =>
      typeof value === "string" && value.length <= limit;
    if (
      !draft ||
      typeof draft !== "object" ||
      !text(draft.requestKey, 36) ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
        draft.requestKey,
      ) ||
      !text(draft.merchant, 200) ||
      !text(draft.address, 500) ||
      !text(draft.date, 10) ||
      !text(draft.currency, 3) ||
      !text(draft.account, 100) ||
      !text(draft.rate, 100) ||
      !Array.isArray(draft.items) ||
      draft.items.length < 1 ||
      draft.items.length > 200 ||
      !draft.items.every(
        (item: ManualReceiptLine) =>
          item &&
          typeof item === "object" &&
          text(item.name, 300) &&
          text(item.quantity, 100) &&
          text(item.unit, 12) &&
          text(item.unit_price, 100) &&
          text(item.total, 100) &&
          text(item.category_id, 100) &&
          typeof item.category_selected === "boolean",
      )
    )
      return null;
    // Explicit projection keeps unknown stored fields out of UI state.
    return {
      requestKey: draft.requestKey,
      merchant: draft.merchant,
      address: draft.address,
      date: draft.date,
      currency: draft.currency,
      account: draft.account,
      rate: draft.rate,
      items: draft.items.map((item: ManualReceiptLine) => ({
        name: item.name,
        quantity: item.quantity,
        unit: item.unit,
        unit_price: item.unit_price,
        total: item.total,
        category_id: item.category_id,
        category_selected: item.category_selected,
      })),
    };
  } catch {
    return null;
  }
}

export function readManualReceiptDraft(storage: DraftStorage, key: string) {
  const raw = storage.getItem(key);
  if (raw === null) return null;
  const draft = parseManualReceiptDraft(raw);
  if (!draft) throw new Error("Unreadable receipt draft");
  return draft;
}
export function saveManualReceiptDraft(
  storage: DraftStorage,
  key: string,
  draft: ManualReceiptDraft,
) {
  const encoded = JSON.stringify(draft);
  if (!parseManualReceiptDraft(encoded))
    throw new Error("Invalid receipt draft");
  storage.setItem(key, encoded);
}
export function clearManualReceiptDraft(storage: DraftStorage, key: string) {
  storage.removeItem(key);
}

export class ManualDraftPersistenceError extends Error {}

// Persist the exact form and retry ID before any request can commit an expense.
export async function submitManualReceiptDraft<T>(
  storage: DraftStorage,
  key: string,
  draft: ManualReceiptDraft,
  post: () => Promise<T>,
): Promise<T> {
  try {
    saveManualReceiptDraft(storage, key, draft);
  } catch {
    throw new ManualDraftPersistenceError("Receipt draft was not saved");
  }
  return post();
}
