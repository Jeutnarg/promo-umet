import {
  UserManager,
  WebStorageStateStore,
  InMemoryWebStorage,
} from "oidc-client-ts";
import { createClient } from "./generated/client";
declare global {
  interface Window {
    __CONFIG__: {
      app: string;
      api: string;
      authority: string;
      clientId: string;
      scope: string;
      adminUrl: string;
      customerUrl: string;
      logoutUrl?: string;
    };
  }
}
export const config = window.__CONFIG__;
export const auth = new UserManager({
  authority: config.authority,
  client_id: config.clientId,
  redirect_uri: window.location.origin + "/",
  post_logout_redirect_uri: window.location.origin + "/",
  response_type: "code",
  scope: config.scope,
  automaticSilentRenew: false,
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
});
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function sendApiRequest<T>(
  path: string,
  method = "GET",
  body?: unknown,
  extra: Record<string, string> = {},
): Promise<T> {
  const user = await auth.getUser();
  if (!user || user.expired)
    throw new ApiError(401, "Your session expired. Sign in again to continue.");
  const response = await fetch(config.api + "/api/v1" + path, {
    method,
    headers: {
      Authorization: `Bearer ${user.access_token}`,
      "Content-Type": "application/json",
      ...extra,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(
      response.status,
      error.message || `Request failed (${response.status})`,
    );
  }
  return response.json() as Promise<T>;
}
export const formatMoney = (cents: number) =>
  new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(
    cents / 100,
  );
export const parseDollarAmount = (value: string) =>
  Math.round(Number(value) * 100);
export const createUniqueId = () => crypto.randomUUID();
export const client = createClient(sendApiRequest);
export async function signOutUser() {
  if (config.logoutUrl) {
    await auth.removeUser();
    location.assign(
      config.logoutUrl +
        "?" +
        new URLSearchParams({
          client_id: config.clientId,
          logout_uri: location.origin + "/",
        }),
    );
  } else await auth.signoutRedirect();
}
