import { useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent, PointerEvent, ReactNode } from "react";
import "./ResizableSidebar.css";

export const MIN_SIDEBAR_WIDTH = 200;
export const MAX_SIDEBAR_WIDTH = 420;

export function clampSidebarWidth(value: number) {
  return Math.max(MIN_SIDEBAR_WIDTH, Math.min(MAX_SIDEBAR_WIDTH, Math.round(value)));
}

type ResizableSidebarProps = {
  width: number;
  onWidthChange: (width: number) => void;
  label: string;
  children: ReactNode;
};

/**
 * Shared wrapper for the collections and settings sidebars. It keeps the width
 * local to this browser while making the resize affordance pointer- and
 * keyboard-accessible.
 */
export function ResizableSidebar({ width, onWidthChange, label, children }: ResizableSidebarProps) {
  const dragRef = useRef<{ pointerId: number; startX: number; startWidth: number } | null>(null);
  const [resizing, setResizing] = useState(false);

  function beginResize(event: PointerEvent<HTMLDivElement>) {
    if (window.matchMedia("(max-width: 820px)").matches) return;
    event.preventDefault();
    dragRef.current = { pointerId: event.pointerId, startX: event.clientX, startWidth: width };
    event.currentTarget.setPointerCapture(event.pointerId);
    setResizing(true);
  }

  function resize(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    onWidthChange(clampSidebarWidth(drag.startWidth + event.clientX - drag.startX));
  }

  function finishResize(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    dragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    setResizing(false);
  }

  function resizeWithKeyboard(event: KeyboardEvent<HTMLDivElement>) {
    let next: number | null = null;
    if (event.key === "ArrowLeft") next = width - (event.shiftKey ? 40 : 16);
    if (event.key === "ArrowRight") next = width + (event.shiftKey ? 40 : 16);
    if (event.key === "Home") next = MIN_SIDEBAR_WIDTH;
    if (event.key === "End") next = MAX_SIDEBAR_WIDTH;
    if (next === null) return;
    event.preventDefault();
    onWidthChange(clampSidebarWidth(next));
  }

  return (
    <div
      className={`resizable-sidebar${resizing ? " is-resizing" : ""}`}
      style={{ "--pbj-sidebar-width": `${width}px` } as CSSProperties}
    >
      {children}
      <div
        className="sidebar-resize-handle"
        role="separator"
        aria-orientation="vertical"
        aria-label={label}
        aria-valuemin={MIN_SIDEBAR_WIDTH}
        aria-valuemax={MAX_SIDEBAR_WIDTH}
        aria-valuenow={width}
        tabIndex={0}
        onPointerDown={beginResize}
        onPointerMove={resize}
        onPointerUp={finishResize}
        onPointerCancel={finishResize}
        onKeyDown={resizeWithKeyboard}
      />
    </div>
  );
}
