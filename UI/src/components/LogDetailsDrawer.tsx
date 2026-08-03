import { Copy, Download, MoreHorizontal, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useDrawerTransition } from "./useDrawerTransition";
import { useModalInteraction } from "./useModalInteraction";
import "./LogDetailsDrawer.css";

export type LogDetailsItem = {
  id: string;
  created: string;
  updated?: string;
  level: number;
  message: string;
  data: Record<string, unknown>;
};

type LogDetailsDrawerProps = {
  log: LogDetailsItem;
  onClose: () => void;
  onNotify?: (message: string, kind?: "ok" | "error") => void;
};

/** Same level thresholds as the official UI (slog levels). */
function logLevel(value: number) {
  if (value >= 8) return { label: "ERROR", kind: "danger" };
  if (value >= 4) return { label: "WARN", kind: "warning" };
  if (value >= 0) return { label: "INFO", kind: "success" };
  return { label: "DEBUG", kind: "" };
}

function formatLocalDate(value: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number, w = 2) => String(n).padStart(w, "0");
  const ms = pad(date.getMilliseconds(), 3);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${ms}`;
}

function formatUtcDate(value: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  // Keep a compact Zulu form like the official panel.
  return date.toISOString().replace("T", " ").replace("Z", "Z");
}

function formatDataValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return "";
  if (key === "execTime" && typeof value === "number") {
    return `${value}ms`;
  }
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function downloadJson(log: LogDetailsItem) {
  const blob = new Blob([JSON.stringify(log, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `pocketbase-log-${log.id}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

type Row = { key: string; value: ReactNode; copyText?: string };

export function LogDetailsDrawer({ log, onClose, onNotify }: LogDetailsDrawerProps) {
  const { t } = useTranslation();
  const { exiting, requestClose, onPanelAnimationEnd } = useDrawerTransition(onClose);
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction(requestClose, {
    active: !exiting
  });
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    function onDoc(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [menuOpen]);

  const level = logLevel(log.level);

  const rows = useMemo<Row[]>(() => {
    const next: Row[] = [
      { key: "id", value: log.id, copyText: log.id },
      {
        key: "level",
        value: (
          <span className={`logd-level logd-level-${level.kind || "debug"}`}>
            <i className="logd-level-dot" aria-hidden="true" />
            {level.label} ({log.level})
          </span>
        ),
        copyText: `${level.label} (${log.level})`
      },
      {
        key: "created",
        value: (
          <span className="logd-created">
            <span>{formatLocalDate(log.created)}</span>
            <span className="logd-created-utc">{formatUtcDate(log.created)}</span>
          </span>
        ),
        copyText: log.created
      }
    ];

    if (log.message) {
      next.push({ key: "message", value: log.message, copyText: log.message });
    }

    const data = log.data ?? {};
    // Prefer a stable, request-friendly order when present; append the rest alphabetically.
    const preferred = [
      "execTime",
      "type",
      "auth",
      "status",
      "method",
      "url",
      "referer",
      "remoteIP",
      "userIP",
      "userAgent",
      "error"
    ];
    const keys = Object.keys(data);
    const ordered = [
      ...preferred.filter((key) => keys.includes(key)),
      ...keys.filter((key) => !preferred.includes(key)).sort()
    ];

    for (const key of ordered) {
      const text = formatDataValue(key, data[key]);
      next.push({
        key: `data.${key}`,
        value: <span className="logd-prewrap">{text}</span>,
        copyText: text
      });
    }

    return next;
  }, [log, level.kind, level.label]);

  async function copyText(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      onNotify?.(t("notifications.copied", "Copied"));
    } catch {
      onNotify?.(t("notifications.copy_failed", "Copy failed"), "error");
    }
  }

  async function copyJson() {
    setMenuOpen(false);
    await copyText(JSON.stringify(log, null, 2));
  }

  return (
    <div
      className={`logd-backdrop${exiting ? " is-exiting" : ""}`}
      role="presentation"
      onMouseDown={exiting ? undefined : onBackdropMouseDown}
      onMouseUp={exiting ? undefined : onBackdropMouseUp}
    >
      <section
        ref={dialogRef}
        className={`logd-drawer${exiting ? " is-exiting" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={t("logs.details_title", "Log details")}
        tabIndex={-1}
        onAnimationEnd={onPanelAnimationEnd}
      >
        <header className="logd-head">
          <h2 className="logd-title">{t("logs.details_title", "Log details")}</h2>
          <div className="logd-head-actions" ref={menuRef}>
            <button
              type="button"
              className="logd-icon-btn"
              onClick={() => setMenuOpen((open) => !open)}
              disabled={exiting}
              title={t("common.options", "Options")}
              aria-label={t("common.options", "Options")}
              aria-expanded={menuOpen}
            >
              <MoreHorizontal size={18} />
            </button>
            {menuOpen && (
              <div className="logd-menu" role="menu">
                <button type="button" role="menuitem" onClick={() => void copyJson()}>
                  <Copy size={15} />
                  {t("actions.copy_json", "Copy JSON")}
                </button>
              </div>
            )}
            <button
              type="button"
              className="logd-icon-btn"
              onClick={requestClose}
              disabled={exiting}
              title={t("actions.close", "Close")}
              aria-label={t("actions.close", "Close")}
            >
              <X size={18} />
            </button>
          </div>
        </header>

        <div className="logd-body">
          {/* Official: inset bordered panel with its own internal width. */}
          <div className="logd-panel">
            <table className="logd-table">
              <tbody>
                {rows.map((row) => (
                  <tr key={row.key}>
                    <th scope="row">{row.key}</th>
                    <td>
                      <div className="logd-cell">
                        <div className="logd-value">{row.value}</div>
                        {row.copyText !== undefined && row.copyText !== "" && (
                          <button
                            type="button"
                            className="logd-copy"
                            onClick={() => void copyText(row.copyText!)}
                            title={t("actions.copy", "Copy")}
                            aria-label={t("actions.copy", "Copy")}
                          >
                            <Copy size={14} />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <footer className="logd-foot">
          <button type="button" className="logd-btn-close" onClick={requestClose} disabled={exiting}>
            {t("actions.close", "Close")}
          </button>
          <button
            type="button"
            className="logd-btn-download"
            onClick={() => downloadJson(log)}
            disabled={exiting}
          >
            <Download size={16} />
            {t("logs.download_json", "Download JSON")}
          </button>
        </footer>
      </section>
    </div>
  );
}

export default LogDetailsDrawer;
