import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ClipboardEvent, ComponentType, DragEvent } from "react";
import { Bold, Code2, FolderOpen, Image, Italic, Link, List, ListOrdered, Quote, RemoveFormatting, Search, Underline, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { RelationFetcher } from "./RelationPicker";
import { useModalInteraction } from "./useModalInteraction";
import "./RichTextEditor.css";

type EditorFileField = {
  name: string;
  type: string;
  thumbs?: string[];
  protected?: boolean;
};

export type EditorFileCollection = {
  id: string;
  name: string;
  fields?: EditorFileField[];
};

export type EditorFileReference = {
  collection: EditorFileCollection;
  recordId: string;
  filename: string;
  thumb?: string;
};

type RichTextEditorProps = {
  name: string;
  value: unknown;
  onChange: (value: string) => void;
  disabled?: boolean;
  fileCollections?: EditorFileCollection[];
  fetchRecords?: RelationFetcher;
  resolveFileUrl?: (reference: EditorFileReference) => string;
};

type ToolbarAction = {
  id: string;
  label: string;
  icon: ComponentType<{ size?: number }>;
  execute: () => void;
};

type SelectionBookmark = {
  start: number;
  end: number;
};

type FileSource = {
  key: string;
  collection: EditorFileCollection;
  field: EditorFileField;
};

type PickerRecord = Record<string, unknown> & { id: string };

type FilePickerProps = {
  collections: EditorFileCollection[];
  fetchRecords: RelationFetcher;
  resolveFileUrl: (reference: EditorFileReference) => string;
  onClose: () => void;
  onInsertFile: (reference: EditorFileReference) => void;
  onInsertUrl: (value: string) => void;
};

const ALLOWED_TAGS = new Set([
  "a",
  "b",
  "blockquote",
  "br",
  "code",
  "del",
  "em",
  "h2",
  "h3",
  "h4",
  "hr",
  "i",
  "img",
  "li",
  "ol",
  "p",
  "pre",
  "s",
  "strong",
  "u",
  "ul"
]);

function rawEditorValue(value: unknown) {
  return value === undefined || value === null ? "" : String(value);
}

function fileSources(collections: EditorFileCollection[]): FileSource[] {
  return collections.flatMap((collection) =>
    (collection.fields ?? [])
      // A protected file cannot be made durable inside an editor body with the
      // Java server's short-lived query token. Offer public files only instead
      // of saving an image URL that will predictably expire.
      .filter((field) => field.type === "file" && !field.protected)
      .map((field) => ({ key: `${collection.id}:${field.name}`, collection, field }))
  );
}

function filenames(value: unknown) {
  if (Array.isArray(value)) return value.map(String).filter(Boolean);
  return value ? [String(value)] : [];
}

function isImageFilename(filename: string) {
  return /\.(avif|gif|jpe?g|png|webp)$/i.test(filename);
}

function safeUrl(value: string, allowedProtocols: string[]) {
  const trimmed = value.trim();
  if (!trimmed) return "";
  if (trimmed.startsWith("/") || trimmed.startsWith("#")) return trimmed;
  try {
    const url = new URL(trimmed, window.location.origin);
    return allowedProtocols.includes(url.protocol) ? url.href : "";
  } catch {
    return "";
  }
}

function semanticInlineTags(style: string) {
  const normalized = style.toLowerCase();
  const tags: string[] = [];
  if (/font-weight\s*:\s*(bold|[6-9]00)\b/.test(normalized)) tags.push("strong");
  if (/font-style\s*:\s*italic\b/.test(normalized)) tags.push("em");
  if (/text-decoration(?:-line)?\s*:[^;]*\bunderline\b/.test(normalized)) tags.push("u");
  return tags;
}

function appendSanitizedNode(source: Node, destination: Node, document: Document) {
  if (source.nodeType === Node.TEXT_NODE) {
    destination.appendChild(document.createTextNode(source.textContent ?? ""));
    return;
  }
  if (source.nodeType !== Node.ELEMENT_NODE) return;

  const element = source as HTMLElement;
  const tag = element.tagName.toLowerCase();
  // Chromium uses styled spans for `execCommand` formatting in some modes.
  // Convert only the three harmless, semantic text styles that the editor
  // itself emits; discard every other style and attribute.
  const formattedTags = tag === "span" ? semanticInlineTags(element.getAttribute("style") ?? "") : [];
  if (!ALLOWED_TAGS.has(tag) && formattedTags.length === 0) {
    for (const child of Array.from(element.childNodes)) appendSanitizedNode(child, destination, document);
    return;
  }

  const clean = document.createElement(formattedTags[0] ?? tag);
  let childDestination: Node = clean;
  for (const formattedTag of formattedTags.slice(1)) {
    const nested = document.createElement(formattedTag);
    childDestination.appendChild(nested);
    childDestination = nested;
  }
  if (tag === "a") {
    const href = safeUrl(element.getAttribute("href") ?? "", ["http:", "https:", "mailto:", "tel:"]);
    if (href) {
      clean.setAttribute("href", href);
      clean.setAttribute("target", "_blank");
      clean.setAttribute("rel", "noopener noreferrer nofollow");
    }
  }
  if (tag === "img") {
    const src = safeUrl(element.getAttribute("src") ?? "", ["http:", "https:"]);
    if (!src) return;
    clean.setAttribute("src", src);
    for (const attribute of ["alt", "title"] as const) {
      const value = element.getAttribute(attribute);
      if (value) clean.setAttribute(attribute, value.slice(0, 500));
    }
    for (const attribute of ["width", "height"] as const) {
      const value = Number.parseInt(element.getAttribute(attribute) ?? "", 10);
      if (Number.isFinite(value) && value > 0 && value <= 4096) clean.setAttribute(attribute, String(value));
    }
  }
  for (const child of Array.from(element.childNodes)) appendSanitizedNode(child, childDestination, document);
  destination.appendChild(clean);
}

/**
 * Sanitizes to a deliberately small HTML subset before it reaches the editable
 * DOM or the record payload. This avoids rendering saved `editor` content with
 * arbitrary attributes, scripting URLs, styles, or executable elements.
 */
export function sanitizeEditorHtml(value: string) {
  const parsed = new DOMParser().parseFromString(value, "text/html");
  const result = parsed.createElement("div");
  for (const node of Array.from(parsed.body.childNodes)) appendSanitizedNode(node, result, parsed);
  return result.innerHTML;
}

/**
 * A Range holds references to individual DOM nodes. Sanitising editor content
 * can replace those nodes between keystrokes, so retaining a cloned Range makes
 * a toolbar command operate at an old caret position. Store selection as text
 * offsets instead and rebuild a fresh Range immediately before the command.
 */
function selectionOffset(root: HTMLElement, node: Node, offset: number) {
  const range = document.createRange();
  try {
    range.setStart(root, 0);
    range.setEnd(node, offset);
    return range.toString().length;
  } catch {
    return null;
  }
}

function textPosition(root: HTMLElement, requestedOffset: number) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let node = walker.nextNode();
  let total = 0;
  let last: Text | null = null;
  while (node) {
    const text = node as Text;
    const length = text.data.length;
    if (requestedOffset <= total + length) {
      return { node: text, offset: Math.max(0, requestedOffset - total) };
    }
    total += length;
    last = text;
    node = walker.nextNode();
  }
  if (last) return { node: last, offset: last.data.length };
  const empty = document.createTextNode("");
  root.appendChild(empty);
  return { node: empty, offset: 0 };
}

function applyInlineFormatFallback(editor: HTMLElement, command: string) {
  const tagName = command === "bold" ? "strong" : command === "italic" ? "em" : command === "underline" ? "u" : "";
  const selection = window.getSelection();
  if (!tagName || !selection || selection.rangeCount === 0) return;
  const range = selection.getRangeAt(0);
  if (range.collapsed || !editor.contains(range.commonAncestorContainer)) return;

  // `surroundContents` throws when the selection crosses an existing inline
  // node. Extracting and reinserting works for both a plain text range and a
  // selection spanning multiple allowed editor nodes.
  const formatted = document.createElement(tagName);
  formatted.appendChild(range.extractContents());
  range.insertNode(formatted);
  const nextRange = document.createRange();
  nextRange.selectNodeContents(formatted);
  selection.removeAllRanges();
  selection.addRange(nextRange);
}

function selectionHasSemanticFormat(editor: HTMLElement, command: string) {
  const tagName = command === "bold" ? "strong, b" : command === "italic" ? "em, i" : command === "underline" ? "u" : "";
  const selection = window.getSelection();
  if (!tagName || !selection || selection.rangeCount === 0) return false;
  const node = selection.getRangeAt(0).commonAncestorContainer;
  const element = node.nodeType === Node.ELEMENT_NODE ? (node as Element) : node.parentElement;
  return Boolean(element?.closest(tagName) && editor.contains(element));
}

function mergePickerRecords(current: PickerRecord[], incoming: PickerRecord[]) {
  const known = new Set(current.map((record) => record.id));
  return [...current, ...incoming.filter((record) => !known.has(record.id))];
}

function RichTextFilePicker({ collections, fetchRecords, resolveFileUrl, onClose, onInsertFile, onInsertUrl }: FilePickerProps) {
  const { t } = useTranslation();
  const sources = useMemo(() => fileSources(collections), [collections]);
  const [sourceKey, setSourceKey] = useState(() => sources[0]?.key ?? "");
  const source = useMemo(() => sources.find((item) => item.key === sourceKey) ?? sources[0], [sourceKey, sources]);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [records, setRecords] = useState<PickerRecord[]>([]);
  const [page, setPage] = useState<{ page: number; totalItems: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [thumb, setThumb] = useState("");
  const [externalUrl, setExternalUrl] = useState("");
  const requestId = useRef(0);
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction<HTMLDivElement>(onClose, { active: true });

  useEffect(() => {
    if (sources.some((item) => item.key === sourceKey)) return;
    setSourceKey(sources[0]?.key ?? "");
  }, [sourceKey, sources]);

  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(searchInput), 220);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    setThumb("");
  }, [source?.key]);

  const load = useCallback(
    async (nextPage: number, nextSearch: string) => {
      const currentSource = source;
      const currentRequest = ++requestId.current;
      if (!currentSource) return;
      setLoading(true);
      setError("");
      try {
        const data = await fetchRecords(currentSource.collection.name, {
          page: nextPage,
          perPage: 50,
          filter: nextSearch
        });
        if (currentRequest !== requestId.current) return;
        const items = data.items as PickerRecord[];
        setRecords((previous) => (nextPage > 1 ? mergePickerRecords(previous, items) : items));
        setPage({ page: data.page, totalItems: data.totalItems });
      } catch (cause) {
        if (currentRequest !== requestId.current) return;
        setError(cause instanceof Error ? cause.message : String(cause));
        setRecords([]);
        setPage(null);
      } finally {
        if (currentRequest === requestId.current) setLoading(false);
      }
    },
    [fetchRecords, source]
  );

  useEffect(() => {
    void load(1, search);
    return () => {
      requestId.current += 1;
    };
  }, [load, search]);

  const resources = useMemo(
    () =>
      source
        ? records.flatMap((record) =>
            filenames(record[source.field.name]).map((filename) => ({ record, filename }))
          )
        : [],
    [records, source]
  );
  const hasMore = Boolean(page && records.length < page.totalItems);
  const thumbOptions = source?.field.thumbs ?? [];

  if (!source) return null;

  return (
    <div
      className="rich-text-file-picker-backdrop"
      role="presentation"
      onMouseDown={onBackdropMouseDown}
      onMouseUp={onBackdropMouseUp}
    >
      <div ref={dialogRef} className="rich-text-file-picker" role="dialog" aria-modal="true" aria-label={t("editor.image", "Insert image")} tabIndex={-1}>
        <header className="rich-text-file-picker-header">
          <strong>{t("editor.image", "Insert image")}</strong>
          <button type="button" className="icon-button" onClick={onClose} title={t("actions.close", "Close")} aria-label={t("actions.close", "Close")}>
            <X size={16} />
          </button>
        </header>
        <div className="rich-text-file-picker-controls">
          <label>
            <span>{t("common.collection", "Collection")}</span>
            <select value={source.key} onChange={(event) => setSourceKey(event.target.value)}>
              {sources.map((item) => (
                <option key={item.key} value={item.key}>{item.collection.name} · {item.field.name}</option>
              ))}
            </select>
          </label>
          {thumbOptions.length > 0 && (
            <label>
              <span>{t("fields.thumbs", "Thumb sizes")}</span>
              <select value={thumb} onChange={(event) => setThumb(event.target.value)}>
                <option value="">—</option>
                {thumbOptions.map((option) => <option key={option} value={option}>{option}</option>)}
              </select>
            </label>
          )}
        </div>
        <label className="rich-text-file-picker-search">
          <Search size={15} />
          <input
            autoFocus
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder={t("actions.search", "Search")}
          />
        </label>
        {error && <p className="form-error">{error}</p>}
        <div className="rich-text-file-picker-grid">
          {resources.length === 0 && !loading ? (
            <p className="relation-empty">{t("common.no_results", "No results")}</p>
          ) : (
            resources.map(({ record, filename }) => {
              const image = isImageFilename(filename);
              const preview = resolveFileUrl({
                collection: source.collection,
                recordId: record.id,
                filename,
                thumb: image && thumb ? thumb : undefined
              });
              return (
                <button
                  key={`${record.id}:${filename}`}
                  type="button"
                  className="rich-text-file-picker-item"
                  onClick={() => onInsertFile({
                    collection: source.collection,
                    recordId: record.id,
                    filename,
                    thumb: image && thumb ? thumb : undefined
                  })}
                >
                  {image ? <img src={preview} alt="" loading="lazy" /> : <FolderOpen size={24} aria-hidden="true" />}
                  <span title={filename}>{filename}</span>
                  <code>{record.id}</code>
                </button>
              );
            })
          )}
        </div>
        <footer className="rich-text-file-picker-footer">
          {hasMore ? (
            <button type="button" className="subtle" disabled={loading} onClick={() => void load((page?.page ?? 1) + 1, search)}>
              {loading ? t("common.loading", "Loading...") : t("records.load_more_short", "Load more")}
            </button>
          ) : <span />}
          <form
            className="rich-text-url-form"
            onSubmit={(event) => {
              event.preventDefault();
              if (externalUrl.trim()) onInsertUrl(externalUrl);
            }}
          >
            <input
              type="url"
              value={externalUrl}
              onChange={(event) => setExternalUrl(event.target.value)}
              placeholder={t("editor.image_prompt", "Enter image URL")}
            />
            <button type="submit" className="subtle" disabled={!externalUrl.trim()}>{t("actions.done", "Done")}</button>
          </form>
        </footer>
      </div>
    </div>
  );
}

export function RichTextEditor({
  name,
  value,
  onChange,
  disabled = false,
  fileCollections = [],
  fetchRecords,
  resolveFileUrl
}: RichTextEditorProps) {
  const { t } = useTranslation();
  const editorRef = useRef<HTMLDivElement | null>(null);
  const lastCommittedRef = useRef<string | null>(null);
  const selectionRef = useRef<SelectionBookmark | null>(null);
  const [filePickerOpen, setFilePickerOpen] = useState(false);
  const canPickFiles = useMemo(
    () => Boolean(fetchRecords && resolveFileUrl && fileSources(fileCollections).length > 0),
    [fetchRecords, fileCollections, resolveFileUrl]
  );

  useEffect(() => {
    const next = sanitizeEditorHtml(rawEditorValue(value));
    if (lastCommittedRef.current === next) return;
    lastCommittedRef.current = next;
    if (editorRef.current && editorRef.current.innerHTML !== next) editorRef.current.innerHTML = next;
  }, [value]);

  function commit() {
    const editor = editorRef.current;
    if (!editor) return;
    // Chromium wraps a new contenteditable line in a <div>. Sanitising that
    // wrapper replaces the editor DOM, which otherwise resets the caret to the
    // start and reverses subsequent typed characters. Save the text offsets
    // first, then restore them after replacing the markup.
    rememberSelection();
    const sanitized = sanitizeEditorHtml(editor.innerHTML);
    if (editor.innerHTML !== sanitized) {
      editor.innerHTML = sanitized;
      restoreSelection();
    }
    lastCommittedRef.current = sanitized;
    onChange(sanitized);
    rememberSelection();
  }

  function rememberSelection() {
    const editor = editorRef.current;
    const selection = window.getSelection();
    if (!editor || !selection || selection.rangeCount === 0) return;
    const range = selection.getRangeAt(0);
    if (!editor.contains(range.commonAncestorContainer)) return;
    const start = selectionOffset(editor, range.startContainer, range.startOffset);
    const end = selectionOffset(editor, range.endContainer, range.endOffset);
    if (start !== null && end !== null) selectionRef.current = { start, end };
  }

  function restoreSelection() {
    const bookmark = selectionRef.current;
    const editor = editorRef.current;
    const selection = window.getSelection();
    if (!bookmark || !editor || !selection) return;
    const start = textPosition(editor, bookmark.start);
    const end = textPosition(editor, bookmark.end);
    const range = document.createRange();
    range.setStart(start.node, start.offset);
    range.setEnd(end.node, end.offset);
    selection.removeAllRanges();
    selection.addRange(range);
  }

  function execute(command: string, value?: string) {
    const editor = editorRef.current;
    if (!editor || disabled) return;
    editor.focus();
    restoreSelection();
    // Ask Chromium for semantic tags when supported. The sanitizer also maps
    // its styled-span fallback to semantic markup without preserving styles.
    const before = editor.innerHTML;
    const inlineCommand = command === "bold" || command === "italic" || command === "underline";
    const alreadyFormatted = inlineCommand && selectionHasSemanticFormat(editor, command);
    document.execCommand("styleWithCSS", false, "false");
    document.execCommand(command, false, value);
    // Browser engines can report a successful execCommand while only toggling
    // an internal typing state. Persisted rich text needs an actual semantic
    // node, so use a narrow DOM fallback for a non-collapsed inline selection.
    if (inlineCommand && !alreadyFormatted && editor.innerHTML === before) {
      // The browser's `input` event can synchronously sanitize a temporary
      // formatting node, leaving Selection anchored in the discarded DOM.
      // Rebuild it from our pre-command offsets before extracting the range.
      restoreSelection();
      applyInlineFormatFallback(editor, command);
    }
    commit();
  }

  function insertLink() {
    const entered = window.prompt(t("editor.link_prompt", "Enter link URL"));
    const href = entered ? safeUrl(entered, ["http:", "https:", "mailto:", "tel:"]) : "";
    if (href) execute("createLink", href);
  }

  function insertImageUrl(value: string) {
    const src = safeUrl(value, ["http:", "https:"]);
    if (!src) return;
    execute("insertImage", src);
    setFilePickerOpen(false);
  }

  function insertImage() {
    if (canPickFiles) {
      setFilePickerOpen(true);
      return;
    }
    const entered = window.prompt(t("editor.image_prompt", "Enter image URL"));
    if (entered) insertImageUrl(entered);
  }

  function insertPickedFile(reference: EditorFileReference) {
    if (!resolveFileUrl) return;
    const url = resolveFileUrl(reference);
    if (isImageFilename(reference.filename)) {
      insertImageUrl(url);
      return;
    }
    const link = document.createElement("a");
    link.href = url;
    link.textContent = reference.filename;
    insertSanitizedHtml(link.outerHTML, reference.filename);
    setFilePickerOpen(false);
  }

  function insertSanitizedHtml(html: string, text: string) {
    const sanitized = html ? sanitizeEditorHtml(html) : "";
    if (sanitized) {
      execute("insertHTML", sanitized);
      return;
    }
    if (text) execute("insertText", text);
  }

  function handlePaste(event: ClipboardEvent<HTMLDivElement>) {
    if (disabled) return;
    event.preventDefault();
    insertSanitizedHtml(event.clipboardData.getData("text/html"), event.clipboardData.getData("text/plain"));
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    if (disabled) return;
    event.preventDefault();
    insertSanitizedHtml(event.dataTransfer.getData("text/html"), event.dataTransfer.getData("text/plain"));
  }

  const actions: ToolbarAction[] = [
    { id: "bold", label: t("editor.bold", "Bold"), icon: Bold, execute: () => execute("bold") },
    { id: "italic", label: t("editor.italic", "Italic"), icon: Italic, execute: () => execute("italic") },
    { id: "underline", label: t("editor.underline", "Underline"), icon: Underline, execute: () => execute("underline") },
    { id: "link", label: t("editor.link", "Insert link"), icon: Link, execute: insertLink },
    { id: "image", label: t("editor.image", "Insert image"), icon: Image, execute: insertImage },
    { id: "bullets", label: t("editor.bulleted_list", "Bulleted list"), icon: List, execute: () => execute("insertUnorderedList") },
    { id: "numbers", label: t("editor.numbered_list", "Numbered list"), icon: ListOrdered, execute: () => execute("insertOrderedList") },
    { id: "quote", label: t("editor.quote", "Quote"), icon: Quote, execute: () => execute("formatBlock", "blockquote") },
    { id: "code", label: t("editor.code_block", "Code block"), icon: Code2, execute: () => execute("formatBlock", "pre") },
    { id: "clear", label: t("actions.clear", "Clear"), icon: RemoveFormatting, execute: () => execute("removeFormat") }
  ];

  return (
    <div className={`rich-text-editor${disabled ? " is-disabled" : ""}`}>
      <div className="rich-text-toolbar" role="toolbar" aria-label={t("editor.toolbar", "Rich text formatting")}>
        {actions.map((action) => {
          const Icon = action.icon;
          return (
            <button
              key={action.id}
              type="button"
              className="icon-button"
              title={action.label}
              aria-label={action.label}
              disabled={disabled}
              onMouseDown={(event) => event.preventDefault()}
              onClick={action.execute}
            >
              <Icon size={15} />
            </button>
          );
        })}
      </div>
      <div
        ref={editorRef}
        className="rich-text-surface"
        data-field-name={name}
        role="textbox"
        aria-label={name}
        aria-multiline="true"
        contentEditable={!disabled}
        suppressContentEditableWarning
        spellCheck
        onInput={commit}
        onKeyUp={rememberSelection}
        onMouseUp={rememberSelection}
        onFocus={rememberSelection}
        onSelect={rememberSelection}
        onPaste={handlePaste}
        onDrop={handleDrop}
      />
      {filePickerOpen && fetchRecords && resolveFileUrl && (
        <RichTextFilePicker
          collections={fileCollections}
          fetchRecords={fetchRecords}
          resolveFileUrl={resolveFileUrl}
          onClose={() => setFilePickerOpen(false)}
          onInsertFile={insertPickedFile}
          onInsertUrl={insertImageUrl}
        />
      )}
    </div>
  );
}
