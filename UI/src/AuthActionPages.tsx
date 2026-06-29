import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";

const DEFAULT_COLLECTION = "users";

type RouteKind =
  | "install"
  | "requestPasswordReset"
  | "confirmPasswordReset"
  | "confirmVerification"
  | "confirmEmailChange"
  | "unknown";

type AuthActionRoute = {
  kind: RouteKind;
  titleKey: string;
  descriptionKey: string;
  collection: string;
  token?: string;
};

type StatusState = {
  kind: "success" | "error";
  message: string;
} | null;

export function AuthActionPages() {
  const { t } = useTranslation();
  const [hash, setHash] = useState(window.location.hash);
  const route = useMemo(() => parseAuthActionRoute(hash), [hash]);
  const [collection, setCollection] = useState(route.collection);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<StatusState>(null);

  useEffect(() => {
    const handler = () => setHash(window.location.hash);
    window.addEventListener("hashchange", handler);
    return () => window.removeEventListener("hashchange", handler);
  }, []);

  useEffect(() => {
    setCollection(route.collection);
    setEmail("");
    setPassword("");
    setPasswordConfirm("");
    setStatus(null);
  }, [route.kind, route.collection, route.token]);

  if (route.kind === "unknown") {
    return null;
  }

  const needsCollection = route.kind !== "install";
  const needsEmail = route.kind === "install" || route.kind === "requestPasswordReset";
  const needsPassword =
    route.kind === "install" ||
    route.kind === "confirmPasswordReset" ||
    route.kind === "confirmEmailChange";
  const needsPasswordConfirm = route.kind === "install" || route.kind === "confirmPasswordReset";

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setStatus(null);

    try {
      const cleanCollection = collection.trim() || DEFAULT_COLLECTION;
      const cleanEmail = email.trim();
      const cleanToken = route.token?.trim();
      const needsToken =
        route.kind === "confirmPasswordReset" ||
        route.kind === "confirmVerification" ||
        route.kind === "confirmEmailChange";

      if (needsEmail && !cleanEmail) {
        throw new Error(t("auth_actions.errors.email_required", "Email is required."));
      }
      if (needsPassword && !password) {
        throw new Error(t("auth_actions.errors.password_required", "Password is required."));
      }
      if (needsPasswordConfirm && password !== passwordConfirm) {
        throw new Error(t("auth_actions.errors.passwords_mismatch", "Passwords do not match."));
      }
      if (needsToken && !cleanToken) {
        throw new Error(t("auth_actions.errors.token_required", "Token is required."));
      }

      switch (route.kind) {
        case "install":
          await postJson("/api/bootstrap/superuser", {
            email: cleanEmail,
            password
          });
          setStatus({ kind: "success", message: t("auth_actions.success.superuser_created", "Superuser created.") });
          break;
        case "requestPasswordReset":
          await postJson(collectionActionPath(cleanCollection, "request-password-reset"), {
            email: cleanEmail
          });
          setStatus({ kind: "success", message: t("auth_actions.success.password_reset_requested", "Password reset request accepted.") });
          break;
        case "confirmPasswordReset":
          await postJson(collectionActionPath(cleanCollection, "confirm-password-reset"), {
            token: cleanToken,
            password,
            passwordConfirm
          });
          setStatus({ kind: "success", message: t("auth_actions.success.password_reset_confirmed", "Password reset confirmed.") });
          break;
        case "confirmVerification":
          await postJson(collectionActionPath(cleanCollection, "confirm-verification"), {
            token: cleanToken
          });
          setStatus({ kind: "success", message: t("auth_actions.success.email_verified", "Email verified.") });
          break;
        case "confirmEmailChange":
          await postJson(collectionActionPath(cleanCollection, "confirm-email-change"), {
            token: cleanToken,
            password
          });
          setStatus({ kind: "success", message: t("auth_actions.success.email_change_confirmed", "Email change confirmed.") });
          break;
        default:
          throw new Error(t("auth_actions.errors.unsupported", "Unsupported auth action."));
      }
    } catch (error) {
      setStatus({ kind: "error", message: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell">
      <main className="auth-layout">
        <section className="auth-copy">
          <div>
            <h2>{t(route.titleKey)}</h2>
            <p className="settings-intro">{t(route.descriptionKey)}</p>
          </div>
          <dl>
            {needsCollection ? (
              <div>
                <dt>{t("common.collection", "Collection")}</dt>
                <dd>{collection || DEFAULT_COLLECTION}</dd>
              </div>
            ) : null}
            {route.token ? (
              <div>
                <dt>{t("common.token", "Token")}</dt>
                <dd>{route.token}</dd>
              </div>
            ) : null}
          </dl>
        </section>

        <form className="auth-form" onSubmit={handleSubmit}>
          {needsCollection ? (
            <label>
              {t("common.collection", "Collection")}
              <input
                value={collection}
                onChange={(event) => setCollection(event.target.value)}
                placeholder={DEFAULT_COLLECTION}
                autoComplete="off"
                required
              />
            </label>
          ) : null}

          {needsEmail ? (
            <label>
              {t("auth.email", "Email")}
              <input
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
                required
              />
            </label>
          ) : null}

          {needsPassword ? (
            <label>
              {route.kind === "confirmEmailChange" ? t("auth.current_password", "Current password") : t("auth.password", "Password")}
              <input
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete={route.kind === "confirmEmailChange" ? "current-password" : "new-password"}
                required
              />
            </label>
          ) : null}

          {needsPasswordConfirm ? (
            <label>
              {t("auth.confirm_password", "Confirm password")}
              <input
                type="password"
                value={passwordConfirm}
                onChange={(event) => setPasswordConfirm(event.target.value)}
                autoComplete="new-password"
                required
              />
            </label>
          ) : null}

          {status ? (
            <div className={status.kind === "error" ? "form-error" : "status ready"} role="status" aria-live="polite">
              {status.message}
            </div>
          ) : null}

          <div className="modal-actions">
            <button
              type="button"
              className="subtle"
              onClick={() => {
                window.location.hash = "";
              }}
            >
              {t("actions.back", "Back")}
            </button>
            <button type="submit" className="primary submit" disabled={busy}>
              {busy ? t("common.submitting", "Submitting...") : submitLabel(route.kind, t)}
            </button>
          </div>
        </form>
      </main>
    </div>
  );
}

function parseAuthActionRoute(hash: string): AuthActionRoute {
  const { path, params } = splitHash(hash);
  const collection = params.get("collection")?.trim() || DEFAULT_COLLECTION;

  if (path.startsWith("#/pbinstall/")) {
    return {
      kind: "install",
      titleKey: "auth_actions.install.title",
      descriptionKey: "auth_actions.install.description",
      collection,
      token: decodeHashPart(path.slice("#/pbinstall/".length))
    };
  }
  if (path === "#/request-password-reset" || path === "#/request-password-reset/") {
    return {
      kind: "requestPasswordReset",
      titleKey: "auth_actions.request_password_reset.title",
      descriptionKey: "auth_actions.request_password_reset.description",
      collection
    };
  }
  if (path.startsWith("#/auth/confirm-password-reset/")) {
    return {
      kind: "confirmPasswordReset",
      titleKey: "auth_actions.confirm_password_reset.title",
      descriptionKey: "auth_actions.confirm_password_reset.description",
      collection,
      token: decodeHashPart(path.slice("#/auth/confirm-password-reset/".length))
    };
  }
  if (path.startsWith("#/auth/confirm-verification/")) {
    return {
      kind: "confirmVerification",
      titleKey: "auth_actions.confirm_verification.title",
      descriptionKey: "auth_actions.confirm_verification.description",
      collection,
      token: decodeHashPart(path.slice("#/auth/confirm-verification/".length))
    };
  }
  if (path.startsWith("#/auth/confirm-email-change/")) {
    return {
      kind: "confirmEmailChange",
      titleKey: "auth_actions.confirm_email_change.title",
      descriptionKey: "auth_actions.confirm_email_change.description",
      collection,
      token: decodeHashPart(path.slice("#/auth/confirm-email-change/".length))
    };
  }
  return {
    kind: "unknown",
    titleKey: "",
    descriptionKey: "",
    collection
  };
}

function splitHash(hash: string): { path: string; params: URLSearchParams } {
  const queryStart = hash.indexOf("?");
  if (queryStart === -1) {
    return { path: hash, params: new URLSearchParams() };
  }
  return {
    path: hash.slice(0, queryStart),
    params: new URLSearchParams(hash.slice(queryStart + 1))
  };
}

function decodeHashPart(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function collectionActionPath(collection: string, action: string): string {
  const name = collection.trim() || DEFAULT_COLLECTION;
  return `/api/collections/${encodeURIComponent(name)}/${action}`;
}

function submitLabel(kind: RouteKind, t: TFunction): string {
  switch (kind) {
    case "install":
      return t("actions.create_superuser", "Create superuser");
    case "requestPasswordReset":
      return t("actions.send_request", "Send request");
    case "confirmPasswordReset":
      return t("actions.reset_password", "Reset password");
    case "confirmVerification":
      return t("actions.verify", "Verify");
    case "confirmEmailChange":
      return t("actions.confirm_change", "Confirm change");
    default:
      return t("actions.submit", "Submit");
  }
}

async function postJson(path: string, body: Record<string, unknown>) {
  const response = await fetch(path, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(body)
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(responseErrorMessage(response, text));
  }
}

function responseErrorMessage(response: Response, text: string): string {
  if (text) {
    try {
      const parsed = JSON.parse(text) as { message?: unknown };
      if (typeof parsed.message === "string" && parsed.message.trim()) {
        return parsed.message;
      }
    } catch {
      return text;
    }
    return text;
  }
  return `${response.status} ${response.statusText}`;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
