import { useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { CodeEditor } from "./CodeEditor";
import { RichTextEditor } from "./RichTextEditor";
import type { EditorFileReference } from "./RichTextEditor";
import { RelationPicker } from "./RelationPicker";
import type { RelationCollection, RelationFetcher, RelationRecord } from "./RelationPicker";
import { GeoPointControl } from "./GeoPointControl";
import { Switch } from "./Switch";
import "./RecordFieldControl.css";
// Types derived from App.tsx
type FieldSchema = {
  id?: string;
  name: string;
  type: string;
  required?: boolean;
  unique?: boolean;
  hidden?: boolean;
  system?: boolean;
  presentable?: boolean;
  collectionId?: string;
  collectionIds?: string[];
  minSelect?: number;
  maxSelect?: number;
  maxFiles?: number;
  maxSize?: number;
  mimeTypes?: string[];
  thumbs?: string[];
  protected?: boolean;
  options?: Record<string, unknown>;
  values?: string[]; // For select fields
};

function maxFiles(field: FieldSchema) {
  const direct = field.maxFiles ?? field.maxSelect;
  const option = Number(field.options?.maxFiles ?? field.options?.maxSelect ?? 1);
  return Math.max(1, Number(direct ?? option ?? 1));
}

function toDatetimeLocalValue(value: unknown) {
  if (typeof value !== "string" || value.trim() === "") return "";
  const date = new Date(value.replaceAll(" ", "T"));
  if (Number.isNaN(date.getTime())) return "";
  const pad = (part: number) => String(part).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

function fromDatetimeLocalValue(value: string) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString().replace("T", " ");
}

function fieldInputValue(value: unknown) {
  if (value === undefined || value === null) return "";
  if (Array.isArray(value)) return value.map(String).join(", ");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function splitCsv(value: string) {
  return value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function selectFieldOptions(field: FieldSchema) {
  const legacyValues = (field as FieldSchema & { values?: unknown }).values;
  const values = Array.isArray(legacyValues) ? legacyValues : field.options?.values;
  return Array.isArray(values) ? values.map(String) : [];
}

type RecordFieldControlProps = {
  field: FieldSchema;
  value: unknown;
  onChange: (value: unknown) => void;
  /** JSON fields keep invalid source text locally; this reports whether it is safe to submit. */
  onValidityChange?: (fieldName: string, valid: boolean) => void;
  /** Increments when the parent explicitly resets/restores the record form. */
  resetVersion?: number;
  collections?: Array<{ id: string; name: string; fields?: FieldSchema[] }>;
  fetchRecords?: RelationFetcher;
  onCreateRelationRecord?: (target: RelationCollection, onSaved: (record: RelationRecord) => void) => void;
  onEditRelationRecord?: (target: RelationCollection, id: string, onSaved: (record: RelationRecord) => void) => void;
  resolveFileUrl?: (reference: EditorFileReference) => string;
};

function jsonFieldSource(value: unknown) {
  if (value === undefined) return "";
  const source = JSON.stringify(value, null, 2);
  return source === undefined ? "" : source;
}

function jsonFieldSignature(value: unknown) {
  if (value === undefined) return "__pbj_undefined__";
  const source = JSON.stringify(value);
  return source === undefined ? "__pbj_undefined__" : source;
}

type JsonFieldControlProps = {
  field: FieldSchema;
  value: unknown;
  meta: ReactNode;
  onChange: (value: unknown) => void;
  onValidityChange?: (fieldName: string, valid: boolean) => void;
  resetVersion?: number;
};

/**
 * A JSON field must preserve a half-typed document without ever making that
 * document part of the record payload.  The parent only receives values that
 * JSON.parse accepted, so form submission cannot silently store malformed JSON
 * as an ordinary string.
 */
function JsonFieldControl({ field, value, meta, onChange, onValidityChange, resetVersion = 0 }: JsonFieldControlProps) {
  const { t } = useTranslation();
  const valueSignature = useMemo(() => jsonFieldSignature(value), [value]);
  const acceptedSignatureRef = useRef(valueSignature);
  const [source, setSource] = useState(() => jsonFieldSource(value));
  const [invalid, setInvalid] = useState(false);

  // Values changed by another control (the full JSON panel, draft restore, or
  // reset) replace the local source. A valid keystroke records its signature
  // first, so this does not reformat the editor while someone is typing.
  useEffect(() => {
    if (acceptedSignatureRef.current === valueSignature) return;
    acceptedSignatureRef.current = valueSignature;
    setSource(jsonFieldSource(value));
    setInvalid(false);
  }, [valueSignature]);

  useEffect(() => {
    acceptedSignatureRef.current = valueSignature;
    setSource(jsonFieldSource(value));
    setInvalid(false);
  }, [resetVersion, valueSignature]);

  useEffect(() => {
    onValidityChange?.(field.name, !invalid);
  }, [field.name, invalid, onValidityChange]);

  function updateSource(nextSource: string) {
    setSource(nextSource);
    try {
      const parsed = nextSource.trim() ? JSON.parse(nextSource) : null;
      acceptedSignatureRef.current = jsonFieldSignature(parsed);
      setInvalid(false);
      onChange(parsed);
    } catch {
      setInvalid(true);
    }
  }

  return (
    <div className={`record-field-card wide record-json-field${invalid ? " has-invalid-json" : ""}`}>
      <span>
        <strong>{field.name}</strong>
        {meta}
      </span>
      <CodeEditor
        name={field.name}
        ariaLabel={field.name}
        language="json"
        value={source}
        onChange={updateSource}
        minHeight={120}
      />
      {invalid && <p className="record-json-field-error" role="alert">{t("errors.invalid_json", "Enter valid JSON before saving.")}</p>}
    </div>
  );
}

export function RecordFieldControl({
  field,
  value,
  onChange,
  onValidityChange,
  resetVersion,
  collections,
  fetchRecords,
  onCreateRelationRecord,
  onEditRelationRecord,
  resolveFileUrl
}: RecordFieldControlProps) {
  const { t } = useTranslation();
  const commonMeta = (
    <span className="record-field-meta">
      {field.type}
      {field.required ? ` / ${t("collections.required", "required")}` : ""}
      {field.unique ? ` / ${t("collections.unique", "unique")}` : ""}
    </span>
  );

  if (field.type === "bool") {
    return (
      <div className="record-field-card checkbox-field">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <Switch name={field.name} checked={Boolean(value)} onChange={(checked) => onChange(checked)} />
      </div>
    );
  }

  if (field.type === "number" || field.type === "autonumber") {
    return (
      <label className="record-field-card">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <input
          name={field.name}
          autoComplete="off"
          type="number"
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(event) => onChange(event.target.value === "" ? null : Number(event.target.value))}
        />
      </label>
    );
  }

  if (field.type === "json") {
    return (
      <JsonFieldControl
        field={field}
        value={value}
        meta={commonMeta}
        onChange={onChange}
        onValidityChange={onValidityChange}
        resetVersion={resetVersion}
      />
    );
  }

  if (field.type === "geoPoint" || field.type === "geopoint") {
    return <GeoPointControl name={field.name} meta={commonMeta} value={value} onChange={onChange} />;
  }

  if (field.type === "editor") {
    return (
      <label className="record-field-card wide">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <RichTextEditor
          name={field.name}
          value={value}
          onChange={onChange}
          fileCollections={collections}
          fetchRecords={fetchRecords}
          resolveFileUrl={resolveFileUrl}
        />
      </label>
    );
  }

  if (field.type === "date" || field.type === "autodate") {
    const dateValue = toDatetimeLocalValue(value);
    return (
      <label className="record-field-card">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <input
          name={field.name}
          type="datetime-local"
          step={1}
          value={dateValue}
          disabled={field.type === "autodate"}
          onChange={(event) => {
            onChange(fromDatetimeLocalValue(event.target.value));
          }}
        />
      </label>
    );
  }

  if (field.type === "select") {
    const isMultiple = maxFiles(field) > 1;
    const options = selectFieldOptions(field);

    if (isMultiple) {
       const maxSelected = maxFiles(field);
       const selectedValues = Array.isArray(value) ? value : (value ? [String(value)] : []);
       return (
          <label className="record-field-card wide">
            <span>
              <strong>{field.name}</strong>
              {commonMeta}
            </span>
            <div className="select-multiple-grid">
               {options.length > 0 ? options.map((opt) => (
                 <label key={opt} className="check-row">
                   <input
                     type="checkbox"
                     checked={selectedValues.includes(opt)}
                     disabled={!selectedValues.includes(opt) && selectedValues.length >= maxSelected}
                     onChange={(e) => {
                       if (e.target.checked) {
                         onChange([...selectedValues, opt]);
                       } else {
                         onChange(selectedValues.filter(v => v !== opt));
                       }
                     }}
                   />
                   {opt}
                 </label>
               )) : <span className="record-field-empty">{t("fields.no_options_configured", "No options configured")}</span>}
            </div>
          </label>
       );
    }
    
    return (
      <label className="record-field-card">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <select
          name={field.name}
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(event) => onChange(event.target.value === "" ? null : event.target.value)}
        >
          <option value="">{t("fields.select_placeholder", "-- Select --")}</option>
          {options.map(opt => <option key={opt} value={opt}>{opt}</option>)}
        </select>
      </label>
    );
  }

  if (field.type === "relation" && collections && fetchRecords) {
    return (
      <label className="record-field-card wide">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <RelationPicker
          field={field}
          value={value}
          collections={collections}
          fetchRecords={fetchRecords}
          onCreateRecord={onCreateRelationRecord}
          onEditRecord={onEditRelationRecord}
          onChange={onChange}
        />
      </label>
    );
  }

  const inputType = field.type === "email" ? "email" : field.type === "url" ? "url" : field.type === "password" ? "password" : "text";
  const relationMulti = field.type === "relation" && maxFiles(field) > 1;
  return (
    <label className="record-field-card">
      <span>
        <strong>{field.name}</strong>
        {commonMeta}
      </span>
      <input
        name={field.name}
        autoComplete="off"
        type={inputType}
        value={fieldInputValue(value)}
        placeholder={relationMulti ? "id1, id2" : ""}
        onChange={(event) => onChange(relationMulti ? splitCsv(event.target.value) : event.target.value)}
      />
    </label>
  );
}
