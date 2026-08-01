import { useEffect, useRef } from "react";
import type { MouseEvent as ReactMouseEvent, RefObject } from "react";

const modalStack: symbol[] = [];

const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])"
].join(",");

type ModalInteractionOptions = {
  /** Keeps the hook dormant while an inline modal is not rendered. */
  active?: boolean;
  /** Prefer this control when the dialog opens (for example a confirmation input). */
  initialFocusRef?: RefObject<HTMLElement | null>;
};

/**
 * Shared behavior for every admin dialog. The stack is deliberately module scoped so
 * a parent dialog cannot also close when a nested confirmation receives Escape.
 */
export function useModalInteraction<T extends HTMLElement = HTMLElement>(
  onClose: () => void,
  options: ModalInteractionOptions = {}
) {
  const { active = true, initialFocusRef } = options;
  const dialogRef = useRef<T | null>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const backdropPressedRef = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!active) return;
    const token = Symbol("modal");
    modalStack.push(token);
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const focusDialog = () => {
      const dialog = dialogRef.current;
      if (!dialog) return;
      const preferred = initialFocusRef?.current;
      const first = dialog.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (preferred ?? first ?? dialog).focus();
    };
    const frame = window.requestAnimationFrame(focusDialog);

    function onKeyDown(event: KeyboardEvent) {
      if (modalStack.at(-1) !== token) return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopImmediatePropagation();
        onCloseRef.current();
        return;
      }
      if (event.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const controls = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
        (element) => !element.hasAttribute("hidden")
      );
      if (controls.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const currentIndex = controls.indexOf(document.activeElement as HTMLElement);
      const targetIndex = event.shiftKey
        ? currentIndex <= 0 ? controls.length - 1 : currentIndex - 1
        : currentIndex === controls.length - 1 ? 0 : currentIndex + 1;
      if (currentIndex === -1 || targetIndex !== currentIndex + (event.shiftKey ? -1 : 1)) {
        event.preventDefault();
        controls[targetIndex].focus();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("keydown", onKeyDown);
      const index = modalStack.lastIndexOf(token);
      if (index >= 0) modalStack.splice(index, 1);
      const previous = previousFocusRef.current;
      window.requestAnimationFrame(() => {
        if (previous?.isConnected) previous.focus();
      });
    };
  }, [active, initialFocusRef]);

  function onBackdropMouseDown(event: ReactMouseEvent<HTMLElement>) {
    backdropPressedRef.current = event.target === event.currentTarget;
  }

  function onBackdropMouseUp(event: ReactMouseEvent<HTMLElement>) {
    const shouldClose = backdropPressedRef.current && event.target === event.currentTarget;
    backdropPressedRef.current = false;
    if (shouldClose && modalStack.length > 0) onCloseRef.current();
  }

  return { dialogRef, onBackdropMouseDown, onBackdropMouseUp };
}
