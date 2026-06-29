import { ChevronDown } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent, ReactNode } from "react";

export type DropdownSelectOption<T extends string | number = string> = {
  value: T;
  label: ReactNode;
  searchText?: string;
  disabled?: boolean;
};

type DropdownSelectProps<T extends string | number = string> = {
  id?: string;
  name?: string;
  value: T;
  options: DropdownSelectOption<T>[];
  onChange: (value: T) => void;
  ariaLabel?: string;
  placeholder?: ReactNode;
  className?: string;
  style?: CSSProperties;
  disabled?: boolean;
  searchThreshold?: number;
};

export function DropdownSelect<T extends string | number = string>({
  id,
  name,
  value,
  options,
  onChange,
  ariaLabel,
  placeholder = "- Select -",
  className = "",
  style,
  disabled = false,
  searchThreshold = 7
}: DropdownSelectProps<T>) {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const selected = options.find((option) => option.value === value);
  const listId = `${id || name || "dropdown-select"}-listbox`;
  const showSearch = options.length >= searchThreshold;

  const visibleOptions = useMemo(() => {
    const normalized = search.toLowerCase().replaceAll(" ", "");
    if (!normalized) return options;
    return options.filter((option) => {
      const text = String(option.searchText ?? option.label ?? option.value).toLowerCase().replaceAll(" ", "");
      return text.includes(normalized);
    });
  }, [options, search]);

  useEffect(() => {
    if (!open) return;
    const selectedIndex = Math.max(visibleOptions.findIndex((option) => option.value === value), 0);
    setActiveIndex(selectedIndex);
  }, [open, value, visibleOptions]);

  useEffect(() => {
    if (!open) return;

    function onPointerDown(event: MouseEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
        setSearch("");
      }
    }

    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  function choose(option: DropdownSelectOption<T>) {
    if (option.disabled) return;
    onChange(option.value);
    setOpen(false);
    setSearch("");
  }

  function moveActive(delta: number) {
    if (!visibleOptions.length) return;
    setActiveIndex((current) => (current + delta + visibleOptions.length) % visibleOptions.length);
  }

  function handleKeyDown(event: KeyboardEvent) {
    if (disabled) return;

    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (!open) setOpen(true);
      else moveActive(1);
      return;
    }

    if (event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) setOpen(true);
      else moveActive(-1);
      return;
    }

    if (event.key === "Home" && open) {
      event.preventDefault();
      setActiveIndex(0);
      return;
    }

    if (event.key === "End" && open) {
      event.preventDefault();
      setActiveIndex(Math.max(visibleOptions.length - 1, 0));
      return;
    }

    if ((event.key === "Enter" || event.key === " ") && open) {
      event.preventDefault();
      const activeOption = visibleOptions[activeIndex];
      if (activeOption) choose(activeOption);
      return;
    }

    if ((event.key === "Enter" || event.key === " ") && !open) {
      event.preventDefault();
      setOpen(true);
      return;
    }

    if (event.key === "Escape" && open) {
      event.preventDefault();
      setOpen(false);
      setSearch("");
    }
  }

  function handleSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Home" || event.key === "End" || event.key === "Enter" || event.key === "Escape") {
      handleKeyDown(event);
    }
  }

  const activeOptionId = open && visibleOptions[activeIndex] ? `${listId}-${activeIndex}` : undefined;

  return (
    <div ref={rootRef} className={`dropdown-select ${open ? "open" : ""} ${disabled ? "disabled" : ""} ${className}`} style={style}>
      <button
        id={id}
        name={name}
        type="button"
        className="dropdown-select-trigger"
        disabled={disabled}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={activeOptionId}
        onClick={() => {
          if (!disabled) setOpen((current) => !current);
        }}
        onKeyDown={handleKeyDown}
      >
        <span className={selected ? "dropdown-select-value" : "dropdown-select-placeholder"}>
          {selected?.label ?? placeholder}
        </span>
        <ChevronDown className="dropdown-select-chevron" size={16} aria-hidden="true" />
      </button>

      {open && (
        <div id={listId} role="listbox" className="dropdown-select-menu" onKeyDown={handleKeyDown}>
          {showSearch && (
            <div className="dropdown-select-search">
              <input
                type="text"
                value={search}
                autoComplete="off"
                placeholder="Search..."
                aria-label="Search options"
                onChange={(event) => setSearch(event.target.value)}
                onKeyDown={handleSearchKeyDown}
              />
            </div>
          )}
          {visibleOptions.length ? (
            visibleOptions.map((option, index) => (
              <button
                key={option.value}
                id={`${listId}-${index}`}
                type="button"
                role="option"
                aria-selected={option.value === value}
                disabled={option.disabled}
                className={`dropdown-select-option ${option.value === value || index === activeIndex ? "active" : ""}`}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => choose(option)}
              >
                {option.label}
              </button>
            ))
          ) : (
            <div className="dropdown-select-empty">No items found</div>
          )}
        </div>
      )}
    </div>
  );
}
