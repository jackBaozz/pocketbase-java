import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import type { InputHTMLAttributes } from "react";
import { useTranslation } from "react-i18next";
import "./PasswordInput.css";

type PasswordInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type">;

/**
 * Keeps password visibility a local, transient UI choice. The underlying value
 * and all autocomplete semantics remain those of the supplied input.
 */
export function PasswordInput({ className, disabled, ...inputProps }: PasswordInputProps) {
  const { t } = useTranslation();
  const [revealed, setRevealed] = useState(false);
  const label = revealed
    ? t("actions.hide_password", "Hide password")
    : t("actions.show_password", "Show password");

  return (
    <span className="password-input">
      <input {...inputProps} className={className} disabled={disabled} type={revealed ? "text" : "password"} />
      <button
        type="button"
        className="password-input-toggle"
        disabled={disabled}
        onClick={() => setRevealed((current) => !current)}
        title={label}
        aria-label={label}
        aria-pressed={revealed}
      >
        {revealed ? <EyeOff size={16} /> : <Eye size={16} />}
      </button>
    </span>
  );
}
