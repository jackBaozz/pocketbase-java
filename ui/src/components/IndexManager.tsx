import { useEffect, useState } from "react";
import { Edit3, GripVertical, Plus, Trash2, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Switch } from "./Switch";
import "./IndexManager.css";

export type ParsedIndex = {
  unique: boolean;
  optional: boolean;
  indexName: string;
  tableName: string;
  columns: string[];
  where: string;
};

const INDEX_REGEX =
  /create\s+(unique\s+)?\s*index\s*(if\s+not\s+exists\s+)?(\S*)\s+on\s+(\S*)\s*\(([\s\S]*)\)(?:\s*where\s+([\s\S]*))?/i;
const QUOTE_REGEX = /^["'`[{]|["'`\]}]$/gm;

/** Port of the official utils.parseIndex, trimmed to the parts this UI edits. */
export function parseIndex(raw: string): ParsedIndex {
  const result: ParsedIndex = {
    unique: false,
    optional: false,
    indexName: "",
    tableName: "",
    columns: [],
    where: ""
  };
  const matches = INDEX_REGEX.exec((raw || "").trim());
  if (!matches || matches.length !== 7) return result;

  result.unique = matches[1]?.trim().toLowerCase() === "unique";
  result.optional = Boolean(matches[2]?.trim());

  const namePair = (matches[3] || "").split(".");
  result.indexName = (namePair.length === 2 ? namePair[1] : namePair[0]).replace(QUOTE_REGEX, "");
  result.tableName = (matches[4] || "").replace(QUOTE_REGEX, "");
  result.where = matches[6]?.trim() || "";

  // Commas inside expressions like max(a, b) must not split columns.
  const rawColumns = (matches[5] || "").replace(/,(?=[^(]*\))/gim, "{PB_TEMP}").split(",");
  for (const column of rawColumns) {
    const name = column.trim().replaceAll("{PB_TEMP}", ",").replace(QUOTE_REGEX, "");
    if (name) result.columns.push(name);
  }
  return result;
}

export function buildIndex(parts: ParsedIndex) {
  const columns = parts.columns.filter(Boolean);
  const name = parts.indexName || `idx_${Math.abs(hashString(parts.tableName + columns.join("_")))}`;
  const quoted = columns.map((column) => (column.includes("(") || column.includes(" ") ? column : `\`${column}\``));
  let sql = `CREATE ${parts.unique ? "UNIQUE " : ""}INDEX ${parts.optional ? "IF NOT EXISTS " : ""}`;
  sql += `\`${name}\` ON \`${parts.tableName}\` (${quoted.join(", ")})`;
  if (parts.where) sql += ` WHERE ${parts.where}`;
  return sql;
}

function hashString(value: string) {
  let hash = 0;
  for (let i = 0; i < value.length; i++) {
    hash = (hash << 5) - hash + value.charCodeAt(i);
    hash |= 0;
  }
  return hash;
}

type IndexManagerProps = {
  indexes: string[];
  collectionName: string;
  fieldNames: string[];
  disabled?: boolean;
  onChange: (indexes: string[]) => void;
};

export function IndexManager({ indexes, collectionName, fieldNames, disabled, onChange }: IndexManagerProps) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState<number | null>(null);
  const [dragging, setDragging] = useState<number | null>(null);
  const [dropTarget, setDropTarget] = useState<number | null>(null);

  function openNew() {
    onChange([
      ...indexes,
      buildIndex({
        unique: false,
        optional: false,
        indexName: `idx_${collectionName}_${indexes.length + 1}`,
        tableName: collectionName,
        columns: [],
        where: ""
      })
    ]);
    setEditing(indexes.length);
  }

  function save(index: number, sql: string) {
    onChange(indexes.map((item, i) => (i === index ? sql : item)));
    setEditing(null);
  }

  function remove(index: number) {
    onChange(indexes.filter((_, i) => i !== index));
    setEditing(null);
  }

  function move(from: number, to: number) {
    if (!Number.isInteger(from) || !Number.isInteger(to) || from === to || from < 0 || to < 0 || from >= indexes.length || to >= indexes.length) return;
    const next = [...indexes];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    onChange(next);
    setEditing((current) => {
      if (current === null) return current;
      if (current === from) return to;
      if (from < current && current <= to) return current - 1;
      if (to <= current && current < from) return current + 1;
      return current;
    });
  }

  return (
    <div className="index-manager">
      <div className="index-manager-header">
        <strong>{t("collections.indexes", "Indexes")}</strong>
        {!disabled && (
          <button type="button" className="subtle index-add" onClick={openNew}>
            <Plus size={14} />
            {t("collections.new_index", "New index")}
          </button>
        )}
      </div>

      {indexes.length === 0 ? (
        <p className="index-empty">
          {t("collections.no_indexes", "No indexes. Add one to enforce uniqueness or speed up queries.")}
        </p>
      ) : (
        <div className="index-list">
          {indexes.map((sql, index) => {
            const parsed = parseIndex(sql);
            return (
              <article
                className={`index-row${dragging === index ? " is-dragging" : ""}${dropTarget === index ? " is-drop-target" : ""}`}
                key={`${parsed.indexName}-${index}`}
                onDragOver={(event) => {
                  if (disabled || dragging === null || dragging === index) return;
                  event.preventDefault();
                  setDropTarget(index);
                }}
                onDragLeave={() => {
                  if (dropTarget === index) setDropTarget(null);
                }}
                onDrop={(event) => {
                  event.preventDefault();
                  const from = dragging ?? Number.parseInt(event.dataTransfer.getData("text/plain"), 10);
                  move(from, index);
                  setDragging(null);
                  setDropTarget(null);
                }}
              >
                {!disabled && indexes.length > 1 && (
                  <button
                    type="button"
                    className="index-drag-handle"
                    draggable
                    onDragStart={(event) => {
                      event.dataTransfer.effectAllowed = "move";
                      event.dataTransfer.setData("text/plain", String(index));
                      setDragging(index);
                    }}
                    onDragEnd={() => {
                      setDragging(null);
                      setDropTarget(null);
                    }}
                    title={t("collections.drag_to_reorder", "Drag to reorder")}
                    aria-label={t("collections.drag_to_reorder", "Drag to reorder")}
                  >
                    <GripVertical size={15} />
                  </button>
                )}
                <div className="index-row-main">
                  <span className="index-name">{parsed.indexName || t("collections.unnamed_index", "(unnamed)")}</span>
                  <code className="index-columns">{parsed.columns.join(", ") || "—"}</code>
                </div>
                <div className="index-row-tags">
                  {parsed.unique && <span className="index-tag unique">UNIQUE</span>}
                  {parsed.where && <span className="index-tag">WHERE</span>}
                </div>
                {!disabled && (
                  <div className="index-row-actions">
                    <button
                      type="button"
                      className="icon-button"
                      onClick={() => setEditing(index)}
                      title={t("collections.edit_index", "Edit index")}
                      aria-label={t("collections.edit_index", "Edit index")}
                    >
                      <Edit3 size={15} />
                    </button>
                    <button
                      type="button"
                      className="icon-button danger"
                      onClick={() => remove(index)}
                      title={t("collections.remove_index", "Remove index")}
                      aria-label={t("collections.remove_index", "Remove index")}
                    >
                      <Trash2 size={15} />
                    </button>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}

      {editing !== null && indexes[editing] !== undefined && (
        <IndexEditor
          sql={indexes[editing]}
          collectionName={collectionName}
          fieldNames={fieldNames}
          onCancel={() => setEditing(null)}
          onSave={(next) => save(editing, next)}
        />
      )}
    </div>
  );
}

type IndexEditorProps = {
  sql: string;
  collectionName: string;
  fieldNames: string[];
  onCancel: () => void;
  onSave: (sql: string) => void;
};

function IndexEditor({ sql, collectionName, fieldNames, onCancel, onSave }: IndexEditorProps) {
  const { t } = useTranslation();
  const [parts, setParts] = useState<ParsedIndex>(() => {
    const parsed = parseIndex(sql);
    return { ...parsed, tableName: parsed.tableName || collectionName };
  });
  const [rawMode, setRawMode] = useState(false);
  const [raw, setRaw] = useState(sql);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        onCancel();
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onCancel]);

  function toggleColumn(name: string) {
    setParts((current) => ({
      ...current,
      columns: current.columns.includes(name)
        ? current.columns.filter((item) => item !== name)
        : [...current.columns, name]
    }));
  }

  const preview = rawMode ? raw : buildIndex(parts);
  const canSave = rawMode ? raw.trim().length > 0 : parts.columns.length > 0;

  return (
    <div className="index-editor-backdrop" role="presentation" onMouseDown={onCancel}>
      <section
        className="index-editor"
        role="dialog"
        aria-modal="true"
        aria-label={t("collections.edit_index", "Edit index")}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="index-editor-header">
          <strong>{t("collections.edit_index", "Edit index")}</strong>
          <button
            type="button"
            className="icon-button"
            onClick={onCancel}
            title={t("actions.close", "Close")}
            aria-label={t("actions.close", "Close")}
          >
            <X size={16} />
          </button>
        </header>

        <div className="index-editor-body">
          {rawMode ? (
            <label>
              {t("collections.index_sql", "Index SQL")}
              <textarea
                className="index-sql"
                value={raw}
                spellCheck={false}
                onChange={(event) => setRaw(event.target.value)}
              />
            </label>
          ) : (
            <>
              <label>
                {t("collections.index_name", "Index name")}
                <input
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  value={parts.indexName}
                  onChange={(event) => setParts({ ...parts, indexName: event.target.value })}
                />
              </label>

              <Switch
                checked={parts.unique}
                onChange={(checked) => setParts({ ...parts, unique: checked })}
                label={t("collections.index_unique", "Unique index")}
              />

              <div className="index-columns-picker">
                <span className="index-picker-label">{t("collections.index_columns", "Columns")}</span>
                <div className="index-column-chips">
                  {fieldNames.map((name) => (
                    <button
                      type="button"
                      key={name}
                      className={`index-column-chip${parts.columns.includes(name) ? " active" : ""}`}
                      onClick={() => toggleColumn(name)}
                    >
                      {name}
                    </button>
                  ))}
                </div>
              </div>

              <label>
                {t("collections.index_where", "WHERE expression (optional)")}
                <input
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  placeholder='eg. status = "active"'
                  value={parts.where}
                  onChange={(event) => setParts({ ...parts, where: event.target.value })}
                />
              </label>
            </>
          )}

          <div className="index-preview">
            <span>{t("collections.index_preview", "Preview")}</span>
            <code>{preview}</code>
          </div>
        </div>

        <footer className="index-editor-actions">
          <button
            type="button"
            className="subtle"
            onClick={() => {
              // Carry the current form state into raw mode so nothing is lost on switch.
              if (!rawMode) setRaw(buildIndex(parts));
              else setParts({ ...parseIndex(raw), tableName: collectionName });
              setRawMode(!rawMode);
            }}
          >
            {rawMode ? t("collections.index_form_mode", "Form mode") : t("collections.index_sql_mode", "Edit SQL")}
          </button>
          <div className="index-editor-actions-right">
            <button type="button" className="subtle" onClick={onCancel}>
              {t("actions.cancel", "Cancel")}
            </button>
            <button type="button" className="primary" disabled={!canSave} onClick={() => onSave(preview)}>
              {t("actions.save", "Save")}
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}
