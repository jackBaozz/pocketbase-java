import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { useTranslation } from "react-i18next";

type CopyButtonProps = {
  /** The text to place on the clipboard when clicked. */
  value: string;
  /** Optional label shown next to the icon. Omit for an icon-only button. */
  label?: string;
  /** Visual variant — matches existing button classes. */
  variant?: "subtle" | "icon" | "compact";
  title?: string;
  /** Optional callback after a successful copy. */
  onCopied?: () => void;
  /** Optional error reporter for unavailable or denied clipboard access. */
  onError?: (error: unknown) => void;
};

/**
 * Button that copies `value` to the clipboard and shows an inline check mark
 * for 500 ms, mirroring the official "copied" affordance. Clipboard failures
 * remain visible to the caller instead of being reported as successful copies.
 */
export function CopyButton({ value, label, variant = "subtle", title, onCopied, onError }: CopyButtonProps) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 500);
      onCopied?.();
    } catch (error) {
      onError?.(error);
    }
  }

  const className =
    variant === "icon"
      ? "icon-button"
      : variant === "compact"
      ? "subtle compact"
      : "subtle";
  const resolvedTitle = title ?? t("actions.copy", "Copy");
  const size = variant === "icon" ? 16 : label ? 16 : 14;

  return (
    <button
      type="button"
      className={className}
      onClick={handleCopy}
      title={copied ? t("notifications.copied", "Copied") : resolvedTitle}
      aria-label={copied ? t("notifications.copied", "Copied") : resolvedTitle}
    >
      {copied ? <Check size={size} /> : <Copy size={size} />}
      {label && <span>{label}</span>}
    </button>
  );
}

export default CopyButton;
