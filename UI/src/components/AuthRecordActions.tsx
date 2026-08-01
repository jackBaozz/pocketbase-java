import { useEffect, useState } from "react";
import { Copy, KeyRound, Link2Off, MailCheck, RotateCcw, ShieldCheck, UserRoundCheck } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ConfirmRequest } from "./ConfirmDialog";
import "./AuthRecordActions.css";

export type AuthRecordLink = {
  id: string;
  provider: string;
  providerId?: string;
  created?: string;
};

export type ImpersonationResult = {
  token: string;
  record: { id: string; [key: string]: unknown };
};

type AuthRecordActionsProps = {
  record: { id: string; email?: unknown; verified?: unknown };
  onConfirm: (request: ConfirmRequest) => Promise<boolean>;
  onRequestVerification: () => Promise<void>;
  onRequestPasswordReset: () => Promise<void>;
  onImpersonate: (duration: number) => Promise<ImpersonationResult>;
  onLoadLinks: () => Promise<AuthRecordLink[]>;
  onUnlink: (link: AuthRecordLink) => Promise<void>;
  onNotify: (message: string, kind?: "ok" | "error") => void;
};

function formatDate(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function AuthRecordActions({
  record,
  onConfirm,
  onRequestVerification,
  onRequestPasswordReset,
  onImpersonate,
  onLoadLinks,
  onUnlink,
  onNotify
}: AuthRecordActionsProps) {
  const { t } = useTranslation();
  const [duration, setDuration] = useState("3600");
  const [links, setLinks] = useState<AuthRecordLink[]>([]);
  const [loadingLinks, setLoadingLinks] = useState(true);
  const [busy, setBusy] = useState("");
  const [result, setResult] = useState<ImpersonationResult | null>(null);

  async function refreshLinks() {
    setLoadingLinks(true);
    try {
      setLinks(await onLoadLinks());
    } catch (error) {
      onNotify(error instanceof Error ? error.message : String(error), "error");
    } finally {
      setLoadingLinks(false);
    }
  }

  useEffect(() => {
    void refreshLinks();
    // The record identity determines the external-auth query; callbacks are stable
    // at the caller and deliberately omitted to avoid refetching on unrelated UI state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record.id]);

  async function run(name: string, action: () => Promise<void>) {
    if (busy) return;
    setBusy(name);
    try {
      await action();
    } catch (error) {
      onNotify(error instanceof Error ? error.message : String(error), "error");
    } finally {
      setBusy("");
    }
  }

  async function impersonate() {
    const parsed = Number(duration);
    const requested = Number.isFinite(parsed) ? Math.trunc(parsed) : 3600;
    const seconds = Math.max(60, Math.min(604800, requested));
    await run("impersonate", async () => {
      const next = await onImpersonate(seconds);
      setResult(next);
      onNotify(t("parity.notifications.impersonation_created", "Impersonation token created"));
    });
  }

  async function unlink(link: AuthRecordLink) {
    const confirmed = await onConfirm({
      title: t("parity.confirm.unlink_auth_title", "Unlink auth provider"),
      message: t("parity.confirm.unlink_auth_body", {
        provider: link.provider,
        defaultValue: "Remove the {{provider}} sign-in link from this account?"
      }),
      confirmLabel: t("parity.actions.unlink", "Unlink"),
      danger: true
    });
    if (!confirmed) return;
    await run(`unlink-${link.id}`, async () => {
      await onUnlink(link);
      await refreshLinks();
      onNotify(t("parity.notifications.auth_provider_unlinked", "Auth provider unlinked"));
    });
  }

  const email = typeof record.email === "string" ? record.email : "";

  return (
    <section className="auth-record-actions">
      <article className="auth-record-action-card">
        <header>
          <div>
            <strong>{t("parity.records.account_actions", "Account actions")}</strong>
            <span>{email || t("parity.records.no_account_email", "No email address on this record")}</span>
          </div>
          <ShieldCheck size={18} />
        </header>
        <div className="auth-record-action-buttons">
          <button type="button" className="subtle" disabled={!email || Boolean(busy)} onClick={() => void run("verification", onRequestVerification)}>
            <MailCheck size={15} />
            {busy === "verification" ? t("common.submitting", "Submitting...") : t("parity.records.send_verification", "Send verification")}
          </button>
          <button type="button" className="subtle" disabled={!email || Boolean(busy)} onClick={() => void run("password-reset", onRequestPasswordReset)}>
            <RotateCcw size={15} />
            {busy === "password-reset" ? t("common.submitting", "Submitting...") : t("parity.records.send_password_reset", "Send password reset")}
          </button>
        </div>
      </article>

      <article className="auth-record-action-card">
        <header>
          <div>
            <strong>{t("parity.records.impersonate", "Impersonate")}</strong>
            <span>{t("parity.records.impersonate_help", "Create a separate short-lived client token without changing your admin session.")}</span>
          </div>
          <UserRoundCheck size={18} />
        </header>
        <div className="auth-record-impersonate-form">
          <label>
            {t("parity.records.token_duration_seconds", "Token duration (seconds)")}
            <input
              type="number"
              min="60"
              max="604800"
              value={duration}
              onChange={(event) => setDuration(event.target.value)}
            />
          </label>
          <button type="button" className="primary" disabled={Boolean(busy)} onClick={() => void impersonate()}>
            <KeyRound size={15} />
            {busy === "impersonate" ? t("common.submitting", "Submitting...") : t("parity.actions.impersonate", "Impersonate")}
          </button>
        </div>
        {result && (
          <div className="auth-record-token-result">
            <code>{result.token}</code>
            <button
              type="button"
              className="icon-button"
              onClick={() => {
                navigator.clipboard.writeText(result.token).then(
                  () => onNotify(t("notifications.copied", "Copied")),
                  (error) => onNotify(error instanceof Error ? error.message : String(error), "error")
                );
              }}
              title={t("actions.copy", "Copy")}
              aria-label={t("actions.copy", "Copy")}
            >
              <Copy size={15} />
            </button>
          </div>
        )}
      </article>

      <article className="auth-record-action-card">
        <header>
          <div>
            <strong>{t("parity.records.linked_auth_providers", "Linked auth providers")}</strong>
            <span>{t("parity.records.linked_auth_providers_help", "External OAuth2 identities currently attached to this account.")}</span>
          </div>
          <KeyRound size={18} />
        </header>
        {loadingLinks ? (
          <p className="auth-record-action-empty">{t("common.loading", "Loading...")}</p>
        ) : links.length === 0 ? (
          <p className="auth-record-action-empty">{t("parity.records.no_linked_auth_providers", "No external auth providers are linked.")}</p>
        ) : (
          <div className="auth-record-link-list">
            {links.map((link) => (
              <div className="auth-record-link-row" key={link.id}>
                <div>
                  <strong>{link.provider}</strong>
                  <span>{link.providerId || link.id}{link.created ? ` · ${formatDate(link.created)}` : ""}</span>
                </div>
                <button type="button" className="subtle danger" disabled={Boolean(busy)} onClick={() => void unlink(link)}>
                  <Link2Off size={15} />
                  {busy === `unlink-${link.id}` ? t("common.submitting", "Submitting...") : t("parity.actions.unlink", "Unlink")}
                </button>
              </div>
            ))}
          </div>
        )}
      </article>
    </section>
  );
}
