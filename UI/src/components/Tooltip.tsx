import { useId, useState, useRef, useEffect, type ReactNode } from "react";
import "./Tooltip.css";

type TooltipProps = {
  /** Tooltip text (or node). */
  content: ReactNode;
  /** Preferred placement; auto-flips when near a viewport edge. */
  placement?: "top" | "bottom" | "left" | "right";
  /** The trigger element. */
  children: ReactNode;
  /** Delay before showing, in ms (default 400). */
  delay?: number;
};

type ResolvedPlacement = NonNullable<TooltipProps["placement"]>;

const FLIP_MAP: Record<ResolvedPlacement, ResolvedPlacement> = {
  top: "bottom",
  bottom: "top",
  left: "right",
  right: "left"
};

/**
 * Lightweight CSS-only tooltip with viewport-aware auto-flipping.
 * Improves on native `title` with consistent styling, no delay variance, and
 * placement control. Renders into a portal-free absolutely-positioned layer.
 */
export function Tooltip({ content, placement = "top", children, delay = 400 }: TooltipProps) {
  const [visible, setVisible] = useState(false);
  const [resolved, setResolved] = useState<ResolvedPlacement>(placement);
  const timerRef = useRef<number | undefined>(undefined);
  const triggerRef = useRef<HTMLSpanElement>(null);
  const tipRef = useRef<HTMLSpanElement>(null);
  const tooltipId = useId();

  useEffect(() => {
    return () => {
      if (timerRef.current) window.clearTimeout(timerRef.current);
    };
  }, []);

  useEffect(() => {
    setResolved(placement);
  }, [placement]);

  function computePlacement() {
    const trigger = triggerRef.current;
    if (!trigger) return;
    const rect = trigger.getBoundingClientRect();
    const margin = 8;
    let next: ResolvedPlacement = placement;
    if (placement === "top" && rect.top < 80) next = FLIP_MAP.top;
    if (placement === "bottom" && rect.bottom > window.innerHeight - 80) next = FLIP_MAP.bottom;
    if (placement === "left" && rect.left < 160) next = FLIP_MAP.left;
    if (placement === "right" && rect.right > window.innerWidth - 160) next = FLIP_MAP.right;
    setResolved(next);
  }

  function show() {
    if (timerRef.current) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      computePlacement();
      setVisible(true);
    }, delay);
  }

  function hide() {
    if (timerRef.current) window.clearTimeout(timerRef.current);
    setVisible(false);
  }

  return (
    <span
      ref={triggerRef}
      className="tooltip-trigger"
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
    >
      {children}
      {visible && (
        <span
          ref={tipRef}
          role="tooltip"
          id={tooltipId}
          className={`tooltip tooltip-${resolved}`}
        >
          {content}
        </span>
      )}
    </span>
  );
}

export default Tooltip;
