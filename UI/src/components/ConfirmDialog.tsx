import { useEffect, useRef, useState } from "react";
import { AlertTriangle, X } from "lucide-react";
import { useTranslation } from "react-i18next";

export type ConfirmRequest = {
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  /** When set, the confirm button stays disabled until the user types this exact text. */
  requireText?: string;
  requireTextHint?: string;
};

type ConfirmDialogProps = ConfirmRequest & {
  onResolve: (confirmed: boolean) => void;
};

export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  danger,
  requireText,
  requireTextHint,
  onResolve
}: ConfirmDialogProps) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const canConfirm = !requireText || typed.trim() === requireText;

  useEffect(() => {
    (requireText ? inputRef.current : confirmRef.current)?.focus();
  }, [requireText]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        onResolve(false);
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onResolve]);

  return (
    <div className="confirm-backdrop" role="presentation" onMouseDown={() => onResolve(false)}>
      <section
        className="confirm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="confirm-header">
          <h3>
            {danger && <AlertTriangle size={16} className="confirm-danger-icon" />}
            {title}
          </h3>
          <button
            type="button"
            className="icon-button"
            onClick={() => onResolve(false)}
            title={t("actions.close", "Close")}
            aria-label={t("actions.close", "Close")}
          >
            <X size={16} />
          </button>
        </header>
        <div className="confirm-body">
          <p>{message}</p>
          {requireText && (
            <label className="confirm-require">
              {requireTextHint ??
                t("confirm.type_to_continue", {
                  text: requireText,
                  defaultValue: 'Type "{{text}}" to continue'
                })}
              <input
                ref={inputRef}
                type="text"
                autoComplete="off"
                spellCheck={false}
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && canConfirm) onResolve(true);
                }}
              />
            </label>
          )}
        </div>
        <footer className="confirm-actions">
          <button type="button" className="subtle" onClick={() => onResolve(false)}>
            {t("actions.cancel", "Cancel")}
          </button>
          <button
            ref={confirmRef}
            type="button"
            className={danger ? "danger" : "primary"}
            disabled={!canConfirm}
            onClick={() => onResolve(true)}
          >
            {confirmLabel ?? t("actions.confirm", "Confirm")}
          </button>
        </footer>
      </section>
    </div>
  );
}
