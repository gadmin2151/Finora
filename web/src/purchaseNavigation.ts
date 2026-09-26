export function categoryPurchasesHash(
  category: string | null | undefined,
  month: string,
): string {
  return `purchases?${new URLSearchParams({ category_id: category || "uncategorized", month })}`;
}

export function purchaseHashFilters(hash: string): {
  category: string;
  month: string;
} {
  const [route, query = ""] = hash.replace(/^#/, "").split("?", 2);
  if (route !== "purchases") return { category: "", month: "" };
  const params = new URLSearchParams(query);
  const category = params.get("category_id") ?? "";
  const month = params.get("month") ?? "";
  return {
    category:
      category === "uncategorized" || /^[a-zA-Z0-9-]{1,80}$/.test(category)
        ? category
        : "",
    month: /^(?:199\d|20\d{2}|2100)-(?:0[1-9]|1[0-2])$/.test(month)
      ? month
      : "",
  };
}
