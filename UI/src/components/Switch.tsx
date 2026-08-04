import type { ReactNode } from "react";
import "./Switch.css";

type SwitchProps = {
  id?: string;
  name?: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
  label?: ReactNode;
  className?: string;
};

/**
 * Modern Switch toggle component replacing legacy standalone checkboxes across forms and settings views.
 */
export function Switch({
  id,
  name,
  checked,
  disabled,
  onChange,
  label,
  className,
}: SwitchProps) {
  return (
    <label
      className={`switch-control ${checked ? "is-checked" : ""} ${
        disabled ? "is-disabled" : ""
      } ${className || ""}`}
    >
      <input
        type="checkbox"
        id={id}
        name={name}
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
        className="switch-native-input"
      />
      <span className="switch-track" aria-hidden="true">
        <span className="switch-thumb" />
      </span>
      {label && <span className="switch-label-text">{label}</span>}
    </label>
  );
}
