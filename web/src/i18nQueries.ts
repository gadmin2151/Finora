import type { QueryClient, Query } from "@tanstack/react-query";

/** Auth has no localized labels. Refetching a failed auth query would unmount login. */
export async function refreshLocalizedQueries(client: QueryClient) {
  const filter = { predicate: (query: Query) => query.queryKey[0] !== "me" };
  await client.cancelQueries(filter);
  await client.invalidateQueries(filter);
}
