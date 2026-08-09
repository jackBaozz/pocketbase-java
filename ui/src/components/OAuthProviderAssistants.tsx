import { useState } from "react";
import { KeyRound, WandSparkles } from "lucide-react";
import { useTranslation } from "react-i18next";
import "./OAuthProviderAssistants.css";

export type AppleClientSecretInput = {
  clientId: string;
  teamId: string;
  keyId: string;
  privateKey: string;
  duration: number;
};

export type OAuthProviderAssistantConfig = {
  authURL?: string;
  tokenURL?: string;
  userInfoURL?: string;
  scopes?: string[];
  pkce?: boolean;
  extra?: Record<string, unknown>;
};

type AppleClientSecretAssistantProps = {
  clientId: string;
  onGenerate: (input: AppleClientSecretInput) => Promise<{ secret: string }>;
  onApplySecret: (secret: string) => void;
};

/**
 * Keeps the Apple private key transient in React state. The Java endpoint signs
 * the JWT with JCA and returns only the generated client secret, which is then
 * saved as the provider's normal PocketBase-compatible clientSecret field.
 */
export function AppleClientSecretAssistant({ clientId, onGenerate, onApplySecret }: AppleClientSecretAssistantProps) {
  const { t } = useTranslation();
  const [teamId, setTeamId] = useState("");
  const [keyId, setKeyId] = useState("");
  const [privateKey, setPrivateKey] = useState("");
  const [duration, setDuration] = useState("15777000");
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");

  async function generate() {
    setMessage("");
    if (!clientId.trim()) {
      setMessage(t("parity.collection.apple_client_id_required", "Enter the Apple Services ID as Client ID first."));
      return;
    }
    const seconds = Number.parseInt(duration, 10);
    if (!Number.isFinite(seconds)) {
      setMessage(t("parity.collection.apple_secret_duration_invalid", "Enter a valid duration in seconds."));
      return;
    }
    setLoading(true);
    try {
      const result = await onGenerate({
        clientId: clientId.trim(),
        teamId: teamId.trim(),
        keyId: keyId.trim(),
        privateKey,
        duration: seconds
      });
      if (!result.secret) throw new Error(t("parity.collection.apple_secret_empty", "The server did not return a client secret."));
      onApplySecret(result.secret);
      // Do not retain signing material once the Java service has generated the JWT.
      setPrivateKey("");
      setMessage(t("parity.collection.apple_secret_generated", "Client secret generated. Save the collection to persist it."));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="oauth-provider-assistant apple-secret-assistant">
      <header>
        <KeyRound size={16} />
        <div>
          <strong>{t("parity.collection.apple_secret_assistant", "Apple client-secret generator")}</strong>
          <span>{t("parity.collection.apple_secret_help", "Generate the ES256 client secret in the Java service. The private key is used only for this request.")}</span>
        </div>
      </header>
      <div className="two-col oauth-provider-assistant-fields">
        <label>
          {t("parity.collection.apple_team_id", "Apple Team ID")}
          <input value={teamId} onChange={(event) => setTeamId(event.target.value)} autoComplete="off" maxLength={10} />
        </label>
        <label>
          {t("parity.collection.apple_key_id", "Apple Key ID")}
          <input value={keyId} onChange={(event) => setKeyId(event.target.value)} autoComplete="off" maxLength={10} />
        </label>
      </div>
      <label>
        {t("parity.collection.apple_private_key", "Private key (.p8 PEM)")}
        <textarea
          value={privateKey}
          onChange={(event) => setPrivateKey(event.target.value)}
          rows={5}
          autoComplete="off"
          spellCheck={false}
        />
      </label>
      <div className="oauth-provider-assistant-actions">
        <label>
          {t("parity.collection.apple_secret_duration", "Secret lifetime (seconds)")}
          <input
            type="number"
            min={1}
            max={15777000}
            value={duration}
            onChange={(event) => setDuration(event.target.value)}
          />
        </label>
        <button type="button" className="subtle" onClick={() => void generate()} disabled={loading}>
          <KeyRound size={15} />
          {loading ? t("common.loading", "Loading...") : t("parity.collection.apple_generate_secret", "Generate client secret")}
        </button>
      </div>
      {message && <p className="oauth-provider-assistant-message" role="status">{message}</p>}
    </section>
  );
}

type OidcDiscoveryAssistantProps = {
  config: OAuthProviderAssistantConfig;
  onApply: (patch: OAuthProviderAssistantConfig) => void;
};

type OidcDiscoveryDocument = {
  authorization_endpoint?: unknown;
  token_endpoint?: unknown;
  userinfo_endpoint?: unknown;
  scopes_supported?: unknown;
  code_challenge_methods_supported?: unknown;
};

function discoveryEndpoint(value: string) {
  const url = new URL(value.trim());
  const localhost = url.hostname === "localhost" || url.hostname === "127.0.0.1" || url.hostname === "[::1]";
  if (url.protocol !== "https:" && !(url.protocol === "http:" && localhost)) {
    throw new Error("OIDC discovery must use HTTPS (HTTP is allowed only for localhost).");
  }
  if (!url.pathname.includes("/.well-known/")) {
    url.pathname = `${url.pathname.replace(/\/$/, "")}/.well-known/openid-configuration`;
  }
  return url;
}

function requiredDiscoveryUrl(value: unknown, name: string) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`OIDC discovery is missing ${name}.`);
  }
  return value.trim();
}

function discoveredScopes(value: unknown, fallback: string[] | undefined) {
  if (Array.isArray(value)) {
    const available = new Set(value.filter((scope): scope is string => typeof scope === "string"));
    const selected = ["openid", "profile", "email"].filter((scope) => available.has(scope));
    if (selected.includes("openid")) return selected;
  }
  return fallback?.length ? fallback : ["openid", "profile", "email"];
}

/**
 * Browser discovery is deliberately opt-in and credential-free. It only fills
 * the standard fields already accepted by the PocketBase OAuth2 provider schema;
 * providers without CORS can still be configured using the editable URL fields.
 */
export function OidcDiscoveryAssistant({ config, onApply }: OidcDiscoveryAssistantProps) {
  const { t } = useTranslation();
  const existingDiscovery = typeof config.extra?.oidcDiscoveryURL === "string" ? config.extra.oidcDiscoveryURL : "";
  const [url, setUrl] = useState(existingDiscovery);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");

  async function discover() {
    setMessage("");
    setLoading(true);
    try {
      const endpoint = discoveryEndpoint(url);
      const response = await fetch(endpoint, {
        headers: { Accept: "application/json" },
        credentials: "omit",
        referrerPolicy: "no-referrer"
      });
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`.trim());
      const document = (await response.json()) as OidcDiscoveryDocument;
      const authURL = requiredDiscoveryUrl(document.authorization_endpoint, "authorization_endpoint");
      const tokenURL = requiredDiscoveryUrl(document.token_endpoint, "token_endpoint");
      const userInfoURL = typeof document.userinfo_endpoint === "string" ? document.userinfo_endpoint.trim() : "";
      const methods = Array.isArray(document.code_challenge_methods_supported)
        ? document.code_challenge_methods_supported
        : [];
      onApply({
        authURL,
        tokenURL,
        userInfoURL,
        scopes: discoveredScopes(document.scopes_supported, config.scopes),
        pkce: methods.some((method) => method === "S256") || config.pkce !== false,
        extra: { ...config.extra, oidcDiscoveryURL: endpoint.toString() }
      });
      setMessage(t("parity.collection.oidc_discovery_success", "OIDC endpoints loaded. Review them, then save the collection."));
    } catch (error) {
      setMessage(
        error instanceof Error
          ? error.message
          : t("parity.collection.oidc_discovery_failed", "Unable to load OIDC discovery. Enter the provider URLs manually.")
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="oauth-provider-assistant oidc-discovery-assistant">
      <header>
        <WandSparkles size={16} />
        <div>
          <strong>{t("parity.collection.oidc_discovery_assistant", "OIDC discovery")}</strong>
          <span>{t("parity.collection.oidc_discovery_help", "Load the standard OpenID Connect configuration without sending your admin token to the provider.")}</span>
        </div>
      </header>
      <div className="oauth-provider-assistant-actions">
        <label>
          {t("parity.collection.oidc_issuer_or_discovery", "Issuer or discovery URL")}
          <input
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            placeholder="https://issuer.example.com"
            autoComplete="url"
          />
        </label>
        <button type="button" className="subtle" onClick={() => void discover()} disabled={loading || !url.trim()}>
          <WandSparkles size={15} />
          {loading ? t("common.loading", "Loading...") : t("parity.collection.oidc_load_discovery", "Load discovery")}
        </button>
      </div>
      <p className="oauth-provider-assistant-hint">
        {t("parity.collection.oidc_discovery_cors_help", "If the issuer blocks browser discovery with CORS, enter the authorization, token and user-info URLs below manually.")}
      </p>
      {message && <p className="oauth-provider-assistant-message" role="status">{message}</p>}
    </section>
  );
}
