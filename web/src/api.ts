import { refreshLocalizedQueries } from "./i18nQueries";
import { t, getLanguage } from "./i18n";
import {
  QueryClient,
  useMutation,
  useQueryClient,
} from "@tanstack/react-query";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, staleTime: 15_000, refetchOnWindowFocus: true },
    mutations: { retry: false },
  },
});
// Language changes refresh server-generated labels without resetting forms or sessions.
window.addEventListener("finora:language", () => {
  void refreshLocalizedQueries(queryClient);
});
let csrf = "";
let organization = "";
let scopeGeneration = 0;
export function setOrganization(value: string) {
  organization = value;
  scopeGeneration += 1;
}
export function organizationUrl(path: string) {
  return `${path}${path.includes("?") ? "&" : "?"}organization_id=${encodeURIComponent(organization)}&language=${getLanguage()}`;
}
export function setCsrf(value: string) {
  csrf = value;
}
export async function clearSession() {
  await queryClient.cancelQueries();
  setCsrf("");
  setOrganization("");
  // Keep the observed auth query so its subscribers receive the logged-out state.
  queryClient.setQueryData(["me"], null);
  queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== "me" });
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const isForm = options.body instanceof FormData;
  const generation = scopeGeneration;
  const requestLanguage = getLanguage();
  const deadline = AbortSignal.timeout(isForm ? 180_000 : 45_000);
  const response = await fetch(`/api${path}`, {
    ...options,
    signal: options.signal
      ? AbortSignal.any([options.signal, deadline])
      : deadline,
    credentials: "same-origin",
    headers: {
      ...(isForm ? {} : { "Content-Type": "application/json" }),
      "X-CSRF-Token": csrf,
      "X-Finora-Client": "web",
      "Accept-Language": requestLanguage,
      ...(organization ? { "X-Organization-ID": organization } : {}),
      ...options.headers,
    },
  }).catch((error: unknown) => {
    if (error instanceof DOMException && error.name === "TimeoutError")
      throw new ApiError(
        t("Сервер не успел ответить. Проверьте соединение и повторите."),
        0,
      );
    throw error;
  });
  if (generation !== scopeGeneration && !path.startsWith("/auth/"))
    throw new DOMException(t("Организация изменена"), "AbortError");
  if (!response.ok) {
    const body: { detail?: string } = await response.json().catch(() => ({}));
    if (response.status === 401 && path !== "/auth/login")
      window.dispatchEvent(new Event("finora:unauthorized"));
    throw new ApiError(
      typeof body.detail === "string"
        ? body.detail
        : t("Не удалось выполнить действие"),
      response.status,
    );
  }
  const body: T = await response.json();
  if (
    (options.method ?? "GET").toUpperCase() === "GET" &&
    !path.startsWith("/auth/") &&
    requestLanguage !== getLanguage()
  )
    throw new DOMException("Interface language changed", "AbortError");
  if (generation !== scopeGeneration && !path.startsWith("/auth/"))
    throw new DOMException(t("Организация изменена"), "AbortError");
  return body;
}
export function send<T = unknown>(
  path: string,
  body?: unknown,
  method = "POST",
) {
  return api<T>(path, {
    method,
    ...(body === undefined
      ? {}
      : { body: body instanceof FormData ? body : JSON.stringify(body) }),
  });
}
export function useAction<T>(
  action: (data: T) => Promise<unknown>,
  onSuccess?: () => void,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: action,
    onSuccess: async () => {
      await client.invalidateQueries();
      onSuccess?.();
    },
  });
}
export function errorText(error: unknown) {
  return error instanceof Error
    ? error instanceof TypeError
      ? t("Нет соединения с сервером. Проверьте интернет и повторите.")
      : error.message
    : t("Не удалось выполнить действие");
}
