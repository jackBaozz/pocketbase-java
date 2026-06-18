import { useEffect, useMemo, useState, type FormEvent } from "react";

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
  title: string;
  description: string;
  collection: string;
  token?: string;
};

type StatusState = {
  kind: "success" | "error";
  message: string;
} | null;

export function AuthActionPages() {
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
        throw new Error("Email is required.");
      }
      if (needsPassword && !password) {
        throw new Error("Password is required.");
      }
      if (needsPasswordConfirm && password !== passwordConfirm) {
        throw new Error("Passwords do not match.");
      }
      if (needsToken && !cleanToken) {
        throw new Error("Token is required.");
      }

      switch (route.kind) {
        case "install":
          await postJson("/api/bootstrap/superuser", {
            email: cleanEmail,
            password
          });
          setStatus({ kind: "success", message: "Superuser created." });
          break;
        case "requestPasswordReset":
          await postJson(collectionActionPath(cleanCollection, "request-password-reset"), {
            email: cleanEmail
          });
          setStatus({ kind: "success", message: "Password reset request accepted." });
          break;
        case "confirmPasswordReset":
          await postJson(collectionActionPath(cleanCollection, "confirm-password-reset"), {
            token: cleanToken,
            password,
            passwordConfirm
          });
          setStatus({ kind: "success", message: "Password reset confirmed." });
          break;
        case "confirmVerification":
          await postJson(collectionActionPath(cleanCollection, "confirm-verification"), {
            token: cleanToken
          });
          setStatus({ kind: "success", message: "Email verified." });
          break;
        case "confirmEmailChange":
          await postJson(collectionActionPath(cleanCollection, "confirm-email-change"), {
            token: cleanToken,
            password
          });
          setStatus({ kind: "success", message: "Email change confirmed." });
          break;
        default:
          throw new Error("Unsupported auth action.");
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
            <h2>{route.title}</h2>
            <p className="settings-intro">{route.description}</p>
          </div>
          <dl>
            {needsCollection ? (
              <div>
                <dt>Collection</dt>
                <dd>{collection || DEFAULT_COLLECTION}</dd>
              </div>
            ) : null}
            {route.token ? (
              <div>
                <dt>Token</dt>
                <dd>{route.token}</dd>
              </div>
            ) : null}
          </dl>
        </section>

        <form className="auth-form" onSubmit={handleSubmit}>
          {needsCollection ? (
            <label>
              Collection
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
              Email
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
              {route.kind === "confirmEmailChange" ? "Current password" : "Password"}
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
              Confirm password
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
              Back
            </button>
            <button type="submit" className="primary submit" disabled={busy}>
              {busy ? "Submitting..." : submitLabel(route.kind)}
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
      title: "Install PocketBase",
      description: "Create the first superuser account for this server.",
      collection,
      token: decodeHashPart(path.slice("#/pbinstall/".length))
    };
  }
  if (path === "#/request-password-reset" || path === "#/request-password-reset/") {
    return {
      kind: "requestPasswordReset",
      title: "Request password reset",
      description: "Send a password reset request for an auth collection.",
      collection
    };
  }
  if (path.startsWith("#/auth/confirm-password-reset/")) {
    return {
      kind: "confirmPasswordReset",
      title: "Confirm password reset",
      description: "Set a new password using the reset token.",
      collection,
      token: decodeHashPart(path.slice("#/auth/confirm-password-reset/".length))
    };
  }
  if (path.startsWith("#/auth/confirm-verification/")) {
    return {
      kind: "confirmVerification",
      title: "Confirm verification",
      description: "Verify the auth record using the email token.",
      collection,
      token: decodeHashPart(path.slice("#/auth/confirm-verification/".length))
    };
  }
  if (path.startsWith("#/auth/confirm-email-change/")) {
    return {
      kind: "confirmEmailChange",
      title: "Confirm email change",
      description: "Confirm the email change with the current account password.",
      collection,
      token: decodeHashPart(path.slice("#/auth/confirm-email-change/".length))
    };
  }
  return {
    kind: "unknown",
    title: "",
    description: "",
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

function submitLabel(kind: RouteKind): string {
  switch (kind) {
    case "install":
      return "Create superuser";
    case "requestPasswordReset":
      return "Send request";
    case "confirmPasswordReset":
      return "Reset password";
    case "confirmVerification":
      return "Verify";
    case "confirmEmailChange":
      return "Confirm change";
    default:
      return "Submit";
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
