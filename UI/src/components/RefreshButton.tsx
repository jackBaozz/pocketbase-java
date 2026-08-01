import { useState } from "react";
import { RefreshCw } from "lucide-react";
import "./RefreshButton.css";

type RefreshButtonProps = {
  onClick: () => void;
  title: string;
  ariaLabel?: string;
  className?: string;
  disabled?: boolean;
  /** Adds the refresh-suggested highlight (page-circle variant). */
  refreshSuggested?: boolean;
  iconSize?: number;
  children?: React.ReactNode;
};

export function RefreshButton({
  onClick,
  title,
  ariaLabel,
  className,
  disabled,
  refreshSuggested,
  iconSize = 17,
  children
}: RefreshButtonProps) {
  const [spinKey, setSpinKey] = useState(0);

  function handleClick() {
    setSpinKey((key) => key + 1);
    onClick();
  }

  return (
    <button
      className={`${className ?? ""}${refreshSuggested ? " refresh-suggested" : ""}`}
      onClick={handleClick}
      disabled={disabled}
      title={title}
      aria-label={ariaLabel ?? title}
    >
      <RefreshCw key={spinKey} className="refresh-button-icon" size={iconSize} />
      {children}
    </button>
  );
}
