import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { ChangeEvent, CSSProperties, JSX, KeyboardEvent, ReactNode } from "react";
import { createPortal } from "react-dom";
import "./CodeEditor.css";

/**
 * Lightweight code editor with syntax highlight + inline autocomplete.
 *
 * Inspired by the official PocketBase code editor behavior (including the
 * v0.39.11 ESC workaround for the TAB focus trap), but without any third-party
 * dependency: the highlighting is a small hand-written scanner
 * and the editing surface is a plain transparent `<textarea>` stacked on top of
 * a `<pre>` that paints the colored tokens.
 *
 * XSS: the highlight layer is built out of React nodes only - we never touch
 * `innerHTML` / `dangerouslySetInnerHTML`, so every user character (`&`, `<`,
 * `>`, quotes...) is emitted as a DOM text node and can never become markup.
 */

/* -------------------------------------------------------------------------- */
/* public types                                                               */
/* -------------------------------------------------------------------------- */

export type CodeEditorLanguage = "pbrule" | "sql" | "json";

export type CodeEditorProps = {
  value: string;
  onChange: (value: string) => void;
  language?: CodeEditorLanguage;
  completions?: string[];
  placeholder?: string;
  disabled?: boolean;
  singleLine?: boolean;
  onSubmit?: () => void;
  minHeight?: number;
  /** Mirrors the underlying textarea's id so `<label for>` keeps working. */
  id?: string;
  name?: string;
  ariaLabel?: string;
};

export type CompletionField = {
  name: string;
  type: string;
  hidden?: boolean;
};

export type CompletionCollection = {
  name: string;
  type: string;
  fields?: CompletionField[];
};

/* -------------------------------------------------------------------------- */
/* tokenizer                                                                  */
/* -------------------------------------------------------------------------- */

type TokenType =
  | "plain"
  | "comment"
  | "string"
  | "number"
  | "constant"
  | "keyword"
  | "operator"
  | "macro"
  | "modifier"
  | "function"
  | "property"
  | "key"
  | "punctuation";

type Token = { type: TokenType; text: string };

/** A scanner rule. Every pattern MUST be sticky (`y`) so it only matches at the cursor. */
type Rule = { type: TokenType; re: RegExp };

const NUMBER = /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/y;

// PocketBase filter/rule expressions - mirrors `Prism.languages.pbrule` plus
// dedicated tokens for the `@request.*` / `@collection.*` macros and for the
// `:isset` / `:changed` / `_via_` modifiers (official renders those italic).
const PBRULE_RULES: Rule[] = [
  { type: "comment", re: /\/\/[^\n]*/y },
  { type: "string", re: /"(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?/y },
  { type: "number", re: NUMBER },
  { type: "constant", re: /\b(?:true|false|null)\b/iy },
  { type: "macro", re: /@[a-zA-Z_][a-zA-Z0-9_]*/y },
  { type: "modifier", re: /:[a-zA-Z_][a-zA-Z0-9_]*/y },
  { type: "function", re: /[a-zA-Z_][a-zA-Z0-9_]*(?=\s*\()/y },
  { type: "property", re: /[a-zA-Z_][a-zA-Z0-9_]*/y },
  // `?=`, `?!=`, ... are the "any/nullable" variants of every comparison operator
  { type: "operator", re: /\?(?:!~|!=|>=|<=|=|~|>|<)|&&|\|\||!~|!=|>=|<=|=|~|>|</y },
  { type: "punctuation", re: /[(){}[\],.]/y },
];

const SQL_KEYWORDS = [
  "select", "from", "where", "and", "or", "not", "in", "like", "glob", "between", "is", "as",
  "join", "inner", "left", "right", "full", "outer", "cross", "on", "using", "natural",
  "group", "by", "having", "order", "asc", "desc", "limit", "offset", "distinct", "all",
  "union", "intersect", "except", "with", "recursive", "case", "when", "then", "else", "end",
  "insert", "into", "values", "update", "set", "delete", "replace", "returning", "conflict",
  "create", "alter", "drop", "table", "view", "index", "trigger", "unique", "if", "exists",
  "primary", "key", "foreign", "references", "default", "check", "constraint", "collate",
  "autoincrement", "cascade", "begin", "commit", "rollback", "transaction", "pragma",
  "explain", "analyze", "vacuum", "attach", "detach", "temp", "temporary", "without", "rowid",
  "cast", "escape", "isnull", "notnull", "nulls", "first", "last", "over", "partition",
].join("|");

const SQL_RULES: Rule[] = [
  { type: "comment", re: /--[^\n]*|\/\*[\s\S]*?(?:\*\/|$)/y },
  // single quotes -> literal, double quotes / backticks / brackets -> quoted identifier
  { type: "string", re: /'(?:''|[^'\n])*'?|"(?:""|[^"\n])*"?|`[^`\n]*`?|\[[^\]\n]*\]?/y },
  { type: "number", re: NUMBER },
  { type: "constant", re: /\b(?:true|false|null)\b/iy },
  { type: "keyword", re: new RegExp(`\\b(?:${SQL_KEYWORDS})\\b`, "iy") },
  { type: "function", re: /[a-zA-Z_][a-zA-Z0-9_$]*(?=\s*\()/y },
  { type: "property", re: /[a-zA-Z_][a-zA-Z0-9_$]*/y },
  { type: "operator", re: /<>|!=|>=|<=|\|\||[-+*/%=<>]/y },
  { type: "punctuation", re: /[(),;.]/y },
];

const JSON_RULES: Rule[] = [
  { type: "key", re: /"(?:\\.|[^"\\\n])*"(?=\s*:)/y },
  { type: "string", re: /"(?:\\.|[^"\\\n])*"?/y },
  { type: "number", re: /-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/y },
  { type: "constant", re: /\b(?:true|false|null)\b/y },
  { type: "punctuation", re: /[{}[\],:]/y },
];

const RULES: Record<CodeEditorLanguage, Rule[]> = {
  pbrule: PBRULE_RULES,
  sql: SQL_RULES,
  json: JSON_RULES,
};

/** Bail out of highlighting for very large documents to keep typing snappy. */
const HIGHLIGHT_LIMIT = 20000;

function scan(source: string, rules: Rule[]): Token[] {
  const tokens: Token[] = [];
  let plainFrom = 0;
  let index = 0;

  while (index < source.length) {
    let matched: Token | null = null;

    for (const rule of rules) {
      rule.re.lastIndex = index;
      const found = rule.re.exec(source);
      if (found && found[0].length > 0) {
        matched = { type: rule.type, text: found[0] };
        break;
      }
    }

    if (!matched) {
      index++;
      continue;
    }

    if (plainFrom < index) {
      tokens.push({ type: "plain", text: source.slice(plainFrom, index) });
    }
    tokens.push(matched);
    index += matched.text.length;
    plainFrom = index;
  }

  if (plainFrom < source.length) {
    tokens.push({ type: "plain", text: source.slice(plainFrom) });
  }

  return tokens;
}

/** `posts_via_author.title` -> `posts` + `_via_` + `author` (official paints `_via_` italic). */
function splitBackRelations(tokens: Token[]): Token[] {
  const result: Token[] = [];

  for (const token of tokens) {
    if (token.type !== "property" || !token.text.includes("_via_")) {
      result.push(token);
      continue;
    }
    for (const part of token.text.split(/(_via_)/)) {
      if (!part) continue;
      result.push({ type: part === "_via_" ? "modifier" : "property", text: part });
    }
  }

  return result;
}

function tokenize(source: string, language: CodeEditorLanguage): Token[] {
  if (!source) {
    return [];
  }
  if (source.length > HIGHLIGHT_LIMIT) {
    return [{ type: "plain", text: source }];
  }

  const tokens = scan(source, RULES[language] ?? PBRULE_RULES);

  return language === "pbrule" ? splitBackRelations(tokens) : tokens;
}

/* -------------------------------------------------------------------------- */
/* word helpers                                                               */
/* -------------------------------------------------------------------------- */

// Same char class as the official editor - `@`, `.` and `:` are part of the
// "word" so that `@request.auth.id` / `field:isset` complete as a single unit.
const COMPLETION_WORD_CHAR = /[\p{Alphabetic}\p{Number}_@:."'{}]/u;
const PLAIN_WORD_CHAR = /[\p{Alphabetic}\p{Number}_$]/u;

export type WordMatch = { word: string; prefix: string; start: number; end: number };

/** Returns the word and prefix surrounding `caret` (`end` is exclusive). */
export function wordAt(text: string, caret: number, charRe: RegExp): WordMatch {
  let start = caret;
  while (start > 0 && charRe.test(text.charAt(start - 1))) {
    start--;
  }

  let end = caret;
  while (end < text.length && charRe.test(text.charAt(end))) {
    end++;
  }

  return {
    word: text.slice(start, end),
    prefix: text.slice(start, caret),
    start,
    end,
  };
}

const MAX_SUGGESTIONS = 30;

/**
 * Case insensitive substring match (same as the official editor), but the
 * candidates that *start* with the typed word are ranked first and, inside each
 * group, the shortest keys win - which is how PocketBase sorts its keys too.
 */
export function filterCompletions(candidates: string[], word: string): string[] {
  const needle = word.toLowerCase();
  if (!needle) {
    return [];
  }

  const prefixed: string[] = [];
  const contained: string[] = [];
  const seen = new Set<string>();

  for (const candidate of candidates) {
    if (!candidate || seen.has(candidate)) continue;
    seen.add(candidate);

    const lowered = candidate.toLowerCase();
    if (lowered === needle) continue; // nothing left to complete

    const at = lowered.indexOf(needle);
    if (at === 0) {
      prefixed.push(candidate);
    } else if (at > 0) {
      contained.push(candidate);
    }
  }

  const byLength = (a: string, b: string) => a.length - b.length || a.localeCompare(b);
  prefixed.sort(byLength);
  contained.sort(byLength);

  return prefixed.concat(contained).slice(0, MAX_SUGGESTIONS);
}

/* -------------------------------------------------------------------------- */
/* caret measuring                                                            */
/* -------------------------------------------------------------------------- */

type CaretRect = { left: number; top: number; bottom: number };

/**
 * Viewport position of the caret.
 *
 * Instead of the usual "hidden mirror div" hack we measure inside the highlight
 * `<pre>`: it already renders the exact same text with the exact same metrics as
 * the textarea, so a DOM Range over its text nodes lands on the real caret. No
 * DOM mutation is involved, so measuring can never disturb the layout.
 */
function caretRect(pre: HTMLElement, index: number): CaretRect | null {
  const walker = document.createTreeWalker(pre, NodeFilter.SHOW_TEXT);

  let consumed = 0;
  let target: Text | null = null;
  let offset = 0;

  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const textNode = node as Text;
    const length = textNode.data.length;
    if (consumed + length >= index) {
      target = textNode;
      offset = index - consumed;
      break;
    }
    consumed += length;
  }

  if (!target || target.data.length === 0) {
    const box = pre.getBoundingClientRect();
    return box.height > 0 ? { left: box.left, top: box.top, bottom: box.bottom } : null;
  }

  const range = document.createRange();

  // A collapsed range reports an empty rect in some engines, so measure a real
  // character: the one before the caret when there is one, the one after
  // otherwise, then take the matching edge.
  if (offset > 0) {
    range.setStart(target, offset - 1);
    range.setEnd(target, offset);
    const rect = range.getBoundingClientRect();
    return { left: rect.right, top: rect.top, bottom: rect.bottom };
  }

  range.setStart(target, 0);
  range.setEnd(target, 1);
  const rect = range.getBoundingClientRect();
  return { left: rect.left, top: rect.top, bottom: rect.bottom };
}

/* -------------------------------------------------------------------------- */
/* component                                                                  */
/* -------------------------------------------------------------------------- */

type SuggestState = {
  items: string[];
  active: number;
  /** replacement range of the word being completed */
  start: number;
  end: number;
  /** caret offset used to anchor the dropdown */
  caret: number;
};

type DropdownPos = { left: number; top: number };

const DEFAULT_MIN_HEIGHT = 82;
const TAB = "\t";
const EMPTY_COMPLETIONS: string[] = [];

export function CodeEditor({
  value,
  onChange,
  language = "pbrule",
  completions = EMPTY_COMPLETIONS,
  placeholder,
  disabled = false,
  singleLine = false,
  onSubmit,
  minHeight = DEFAULT_MIN_HEIGHT,
  id,
  name,
  ariaLabel,
}: CodeEditorProps): JSX.Element {
  const text = typeof value === "string" ? value : "";

  const wrapRef = useRef<HTMLDivElement | null>(null);
  const preRef = useRef<HTMLPreElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const dropdownRef = useRef<HTMLDivElement | null>(null);
  const activeItemRef = useRef<HTMLButtonElement | null>(null);

  /** selection to restore once React has re-rendered with the new value */
  const pendingSelectionRef = useRef<[number, number] | null>(null);
  /** set while we mutate the value ourselves so the edit does not re-open the dropdown */
  const suppressRef = useRef(false);

  const debounceTimerRef = useRef<number | null>(null);
  const [suggest, setSuggest] = useState<SuggestState | null>(null);
  const [tabTrapped, setTabTrapped] = useState(true);
  const [pos, setPos] = useState<DropdownPos | null>(null);

  const listId = useId();
  const tokens = useMemo(() => tokenize(text, language), [text, language]);

  const highlightNodes = useMemo<ReactNode[]>(() => {
    const nodes: ReactNode[] = tokens.map((token, index) =>
      token.type === "plain" ? (
        token.text
      ) : (
        <span key={index} className={`pbce-t pbce-t-${token.type}`}>
          {token.text}
        </span>
      )
    );

    // A trailing newline does not always generate a line box, so the overlay
    // would be one line shorter than the textarea. The sentinel keeps the two
    // layers the exact same height (and gives the caret room on the last line).
    if (!singleLine) {
      nodes.push("\n");
    }

    return nodes;
  }, [tokens, singleLine]);

  const closeSuggest = useCallback(() => {
    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    setSuggest(null);
    setPos(null);
  }, []);

  /* ---------------------------------------------------------------- editing */

  // Restore the caret after a programmatic edit (runs before paint, no flicker).
  useLayoutEffect(() => {
    const selection = pendingSelectionRef.current;
    const input = inputRef.current;
    if (!selection || !input) {
      return;
    }
    pendingSelectionRef.current = null;
    input.setSelectionRange(selection[0], selection[1]);
  });

  /**
   * Replaces `[start, end)` with `insert`.
   *
   * `execCommand("insertText")` is deprecated but it is still the only way to
   * keep the native undo/redo stack of a textarea intact - we fall back to a
   * plain controlled update when it is unavailable.
   */
  const replaceRange = useCallback(
    (start: number, end: number, insert: string, selection?: [number, number]) => {
      const input = inputRef.current;
      if (!input || disabled) {
        return;
      }

      const caret = start + insert.length;
      const nextSelection: [number, number] = selection ?? [caret, caret];
      pendingSelectionRef.current = nextSelection;

      input.focus();
      input.setSelectionRange(start, end);

      suppressRef.current = true;
      let handled = false;
      try {
        handled = document.execCommand("insertText", false, insert);
      } catch {
        handled = false;
      }

      if (handled) {
        // the synchronous `input` event already pushed the new value upstream
        input.setSelectionRange(nextSelection[0], nextSelection[1]);
        return;
      }

      suppressRef.current = false;
      onChange(text.slice(0, start) + insert + text.slice(end));
    },
    [disabled, onChange, text]
  );

  const acceptSuggestion = useCallback(
    (item: string) => {
      if (!suggest) {
        return;
      }
      closeSuggest();
      replaceRange(suggest.start, suggest.end, item);
    },
    [closeSuggest, replaceRange, suggest]
  );

  /** Tab / Shift+Tab. Indents by lines as soon as more than the caret is involved. */
  const indent = useCallback(
    (outdent: boolean) => {
      const input = inputRef.current;
      if (!input) {
        return;
      }

      const start = input.selectionStart;
      const end = input.selectionEnd;

      if (!outdent && start === end) {
        replaceRange(start, end, TAB);
        return;
      }

      // a selection ending right after a line break should not drag the next line in
      const scopeEnd = end > start && text.charAt(end - 1) === "\n" ? end - 1 : end;
      const lineStart = start === 0 ? 0 : text.lastIndexOf("\n", start - 1) + 1;
      const nextBreak = text.indexOf("\n", scopeEnd);
      const lineEnd = nextBreak === -1 ? text.length : nextBreak;

      let firstDelta = 0;
      let totalDelta = 0;

      const lines = text.slice(lineStart, lineEnd).split("\n").map((line, i) => {
        if (outdent) {
          const stripped = /^(?:\t| {1,4})/.exec(line);
          if (!stripped) {
            return line;
          }
          const width = stripped[0].length;
          if (i === 0) firstDelta -= width;
          totalDelta -= width;
          return line.slice(width);
        }

        if (i === 0) firstDelta += TAB.length;
        totalDelta += TAB.length;
        return TAB + line;
      });

      if (totalDelta === 0) {
        return;
      }

      const selStart = Math.max(lineStart, start + firstDelta);
      const selEnd = Math.max(selStart, end + totalDelta);
      replaceRange(lineStart, lineEnd, lines.join("\n"), [selStart, selEnd]);
    },
    [replaceRange, text]
  );

  const selectCurrentWord = useCallback(() => {
    const input = inputRef.current;
    if (!input) {
      return;
    }
    const match = wordAt(text, input.selectionStart, PLAIN_WORD_CHAR);
    if (match.end > match.start) {
      input.setSelectionRange(match.start, match.end);
    }
  }, [text]);

  /* -------------------------------------------------------------- suggesting */

  const openSuggest = useCallback(
    (source: string, caret: number) => {
      if (disabled || !completions.length || !source.length) {
        closeSuggest();
        return;
      }

      const match = wordAt(source, caret, COMPLETION_WORD_CHAR);

      // nothing typed yet, or the caret sits at the very beginning of an already
      // typed word - same guard as the official editor
      if (!match.word.length || caret === match.start) {
        closeSuggest();
        return;
      }

      const query = match.prefix || match.word;
      const items = filterCompletions(completions, query);

      // Don't show if no items, or if the only suggestion is already the exact full word
      if (!items.length || (items.length === 1 && items[0] === match.word)) {
        closeSuggest();
        return;
      }

      setSuggest({ items, active: 0, start: match.start, end: match.end, caret });
    },
    [closeSuggest, completions, disabled]
  );

  const updatePosition = useCallback(() => {
    const pre = preRef.current;
    const wrap = wrapRef.current;
    if (!suggest || !pre || !wrap) {
      return;
    }

    const caret = caretRect(pre, suggest.caret);
    if (!caret) {
      setPos(null);
      return;
    }

    const box = wrap.getBoundingClientRect();
    // `innerHeight` first: `documentElement.clientHeight` reports 0 in a few
    // embedded/zero-sized hosts, which would break the clamping below
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
    const viewportWidth = window.innerWidth || document.documentElement.clientWidth;

    // hide while the caret is scrolled out of the editor, or the editor itself
    // is out of the viewport (skipped when the viewport size is unknown)
    const caretInside = caret.bottom > box.top && caret.top < box.bottom;
    const editorInside = !viewportHeight || (box.bottom > 0 && box.top < viewportHeight);
    if (!caretInside || !editorInside) {
      setPos(null);
      return;
    }

    const width = dropdownRef.current?.offsetWidth ?? 0;
    const height = dropdownRef.current?.offsetHeight ?? 0;

    let left = Math.max(0, caret.left - 4);
    let top = caret.bottom + 2;

    // flip above the caret / pull back from the right edge when it would overflow
    if (viewportHeight && top + height > viewportHeight) {
      top = Math.max(0, caret.top - height - 2);
    }
    if (viewportWidth && left + width > viewportWidth) {
      left = Math.max(0, viewportWidth - width - 4);
    }

    setPos((prev) => (prev && prev.left === left && prev.top === top ? prev : { left, top }));
  }, [suggest]);

  // position after the overlay has been re-rendered with the current value
  useLayoutEffect(() => {
    updatePosition();
  }, [updatePosition, text]);

  // keep the dropdown glued to the caret while anything scrolls / resizes
  useEffect(() => {
    if (!suggest) {
      return;
    }

    const reposition = () => updatePosition();
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node | null;
      if (dropdownRef.current?.contains(target) || wrapRef.current?.contains(target)) {
        return;
      }
      closeSuggest();
    };

    window.addEventListener("scroll", reposition, true);
    window.addEventListener("resize", reposition);
    document.addEventListener("mousedown", onPointerDown, true);

    return () => {
      window.removeEventListener("scroll", reposition, true);
      window.removeEventListener("resize", reposition);
      document.removeEventListener("mousedown", onPointerDown, true);
    };
  }, [closeSuggest, suggest, updatePosition]);

  useEffect(() => {
    activeItemRef.current?.scrollIntoView({ block: "nearest" });
  }, [suggest]);

  // never leave a dropdown behind
  useEffect(() => closeSuggest, [closeSuggest]);
  useEffect(() => {
    if (disabled) {
      closeSuggest();
    }
  }, [closeSuggest, disabled]);

  /* ----------------------------------------------------------------- events */

  const handleChange = (event: ChangeEvent<HTMLTextAreaElement>) => {
    const input = event.currentTarget;
    const raw = input.value;
    const next = singleLine ? raw.replace(/[\r\n]+/g, " ") : raw;

    onChange(next);

    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }

    if (suppressRef.current) {
      suppressRef.current = false;
      closeSuggest();
      return;
    }
    if (next !== raw) {
      // sanitized paste - offsets no longer match, just stay quiet
      closeSuggest();
      return;
    }

    if (!next.length || disabled || !completions.length) {
      closeSuggest();
      return;
    }

    const caret = input.selectionStart ?? next.length;

    debounceTimerRef.current = window.setTimeout(() => {
      debounceTimerRef.current = null;
      const currentInput = inputRef.current;
      if (!currentInput || document.activeElement !== currentInput) {
        closeSuggest();
        return;
      }
      const currentCaret = currentInput.selectionStart ?? caret;
      openSuggest(currentInput.value, currentCaret);
    }, 50);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    const key = event.key;

    if (suggest) {
      // the legacy names are still emitted by a few browsers / automation tools
      if (key === "Escape" || key === "Esc") {
        event.preventDefault();
        closeSuggest();
        return;
      }
      if (key === "ArrowDown" || key === "Down") {
        event.preventDefault();
        setSuggest((prev) =>
          prev ? { ...prev, active: Math.min(prev.active + 1, prev.items.length - 1) } : prev
        );
        return;
      }
      if (key === "ArrowUp" || key === "Up") {
        event.preventDefault();
        setSuggest((prev) => (prev ? { ...prev, active: Math.max(prev.active - 1, 0) } : prev));
        return;
      }
      if (key === "Enter" || key === "Tab") {
        event.preventDefault();
        acceptSuggestion(suggest.items[suggest.active]);
        return;
      }
    }

    if ((event.ctrlKey || event.metaKey) && key.toLowerCase() === "d") {
      event.preventDefault();
      selectCurrentWord();
      return;
    }

    // Ctrl/Cmd+A selects the whole editor content instead of the page.
    if ((event.ctrlKey || event.metaKey) && key.toLowerCase() === "a") {
      event.preventDefault();
      const target = event.currentTarget;
      target.setSelectionRange(0, target.value.length);
      return;
    }

    if (key === "Escape" || key === "Esc") {
      setTabTrapped(false);
      return;
    }

    if (key === "Tab" && !singleLine) {
      if (tabTrapped) {
        event.preventDefault();
        indent(event.shiftKey);
        return;
      }
      setTabTrapped(true);
      return;
    }

    setTabTrapped(true);

    if (key === "Enter" && singleLine) {
      event.preventDefault();
      onSubmit?.();
    }
  };

  /* ----------------------------------------------------------------- render */

  const className = [
    "pbce",
    `pbce--${language}`,
    singleLine ? "pbce--single" : "pbce--multi",
    disabled ? "pbce--disabled" : "",
  ]
    .filter(Boolean)
    .join(" ");

  const style = { "--pbce-min-height": `${Math.max(0, minHeight)}px` } as CSSProperties;

  return (
    <div
      ref={wrapRef}
      className={className}
      style={style}
      onMouseDown={(event) => {
        // clicking the padding / border area should still focus the editor
        if (event.target === event.currentTarget) {
          event.preventDefault();
          inputRef.current?.focus();
        }
      }}
    >
      <div className="pbce-body">
        <pre ref={preRef} className="pbce-pre" aria-hidden="true">
          {highlightNodes}
        </pre>
        <textarea
          ref={inputRef}
          className="pbce-input"
          id={id}
          name={name}
          aria-label={ariaLabel}
          value={text}
          rows={1}
          disabled={disabled}
          placeholder={placeholder}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          autoComplete="off"
          data-gramm="false"
          wrap={singleLine ? "off" : "soft"}
          aria-autocomplete="list"
          aria-expanded={suggest ? true : undefined}
          aria-controls={suggest ? listId : undefined}
          aria-activedescendant={suggest ? `${listId}-${suggest.active}` : undefined}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          onBlur={closeSuggest}
        />
      </div>

      {suggest && !disabled
        ? createPortal(
            <div
              ref={dropdownRef}
              id={listId}
              role="listbox"
              className="pbce-dropdown"
              style={{
                left: `${pos?.left ?? 0}px`,
                top: `${pos?.top ?? 0}px`,
                visibility: pos ? "visible" : "hidden",
              }}
              // keep the focus (and therefore the caret) inside the textarea
              onMouseDown={(event) => event.preventDefault()}
            >
              {suggest.items.map((item, index) => (
                <button
                  key={item}
                  ref={index === suggest.active ? activeItemRef : undefined}
                  id={`${listId}-${index}`}
                  type="button"
                  role="option"
                  aria-selected={index === suggest.active}
                  className={`pbce-dropdown-item${index === suggest.active ? " active" : ""}`}
                  onClick={() => acceptSuggestion(item)}
                  onMouseEnter={() =>
                    setSuggest((prev) => (prev ? { ...prev, active: index } : prev))
                  }
                >
                  {item}
                </button>
              ))}
            </div>,
            document.body
          )
        : null}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* rule completions                                                           */
/* -------------------------------------------------------------------------- */

const BASE_IDENTIFIERS = ["id", "created", "updated"];
const AUTH_IDENTIFIERS = ["email", "emailVisibility", "verified"];

const REQUEST_KEYS = [
  "@request.context",
  "@request.method",
  "@request.query.",
  "@request.body.",
  "@request.headers.",
  "@request.auth.id",
  "@request.auth.collectionId",
  "@request.auth.collectionName",
];

/** Extra `:modifier` suffixes a field supports inside a filter expression. */
function fieldModifiers(type: string): string[] {
  switch (type) {
    case "text":
    case "email":
    case "url":
    case "editor":
      return ["lower"];
    case "select":
    case "file":
    case "relation":
      return ["each", "length"];
    default:
      return [];
  }
}

function isCompletableField(field: CompletionField): boolean {
  return !!field?.name && !field.hidden && field.type !== "password";
}

function collectFieldKeys(collection: CompletionCollection, prefix: string, out: string[]): void {
  for (const identifier of BASE_IDENTIFIERS) {
    out.push(prefix + identifier);
  }
  if (collection.type === "auth") {
    for (const identifier of AUTH_IDENTIFIERS) {
      out.push(prefix + identifier);
    }
  }

  for (const field of collection.fields ?? []) {
    if (!isCompletableField(field)) continue;
    out.push(prefix + field.name);
    for (const modifier of fieldModifiers(field.type)) {
      out.push(`${prefix}${field.name}:${modifier}`);
    }
  }
}

/**
 * Autocomplete keys for a PocketBase API rule, following
 * `collectionAutocompleteKeys()` of the official UI:
 * the collection own fields, the `@request.*` keys (including the
 * `:isset` / `:changed` body modifiers) and the `@collection.*` joins.
 */
export function buildRuleCompletions(
  collection: CompletionCollection | null | undefined,
  allCollections: CompletionCollection[] = []
): string[] {
  const keys: string[] = [];

  if (collection) {
    collectFieldKeys(collection, "", keys);
  }

  for (const key of REQUEST_KEYS) {
    keys.push(key);
  }

  // @request.auth.* -> every non system auth collection
  for (const candidate of allCollections) {
    if (candidate?.type !== "auth" || candidate.name?.startsWith("_")) continue;
    collectFieldKeys(candidate, "@request.auth.", keys);
  }

  // @request.body.* -> the edited collection, with the special modifiers
  if (collection) {
    const body: string[] = [];
    collectFieldKeys(collection, "@request.body.", body);
    for (const key of body) {
      keys.push(key);
      if (!key.includes(":")) {
        keys.push(`${key}:isset`);
        keys.push(`${key}:changed`);
      }
    }
  }

  // @collection.<name>.*
  for (const candidate of allCollections) {
    if (!candidate?.name || candidate.name.startsWith("_")) continue;
    collectFieldKeys(candidate, `@collection.${candidate.name}.`, keys);
  }

  const unique = Array.from(new Set(keys));
  unique.sort((a, b) => a.length - b.length || a.localeCompare(b));

  return unique;
}
