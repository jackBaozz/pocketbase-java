import React from 'react';
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
  return field.maxFiles || (field.type === "relation" ? field.maxSelect || 1 : 1);
}

function fieldInputValue(value: unknown) {
  if (value === undefined || value === null) return "";
  if (Array.isArray(value)) return value.join(", ");
  return String(value);
}

function splitCsv(value: string) {
  return value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function selectFieldOptions(field: FieldSchema) {
  const optionValues = field.options?.values;
  const values = Array.isArray(field.values) ? field.values : optionValues;
  return Array.isArray(values) ? values.map(String) : [];
}

type RecordFieldControlProps = {
  field: FieldSchema;
  value: unknown;
  onChange: (value: unknown) => void;
};

export function RecordFieldControl({ field, value, onChange }: RecordFieldControlProps) {
  const commonMeta = (
    <span className="record-field-meta">
      {field.type}
      {field.required ? " / required" : ""}
      {field.unique ? " / unique" : ""}
    </span>
  );

  if (field.type === "bool") {
    return (
      <label className="record-field-card checkbox-field">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <input name={field.name} type="checkbox" checked={Boolean(value)} onChange={(event) => onChange(event.target.checked)} />
      </label>
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
      <label className="record-field-card wide">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <textarea
          name={field.name}
          className="compact-textarea"
          value={value === undefined ? "" : typeof value === "string" ? value : JSON.stringify(value, null, 2)}
          onChange={(event) => {
            const raw = event.target.value;
            try {
              onChange(raw.trim() ? JSON.parse(raw) : null);
            } catch {
              onChange(raw);
            }
          }}
          spellCheck={false}
        />
      </label>
    );
  }

  if (field.type === "editor") {
    return (
      <label className="record-field-card wide">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <textarea
          name={field.name}
          className="compact-textarea"
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(event) => onChange(event.target.value)}
        />
      </label>
    );
  }

  if (field.type === "date" || field.type === "autodate") {
    const dateValue = typeof value === "string" ? value.substring(0, 16) : ""; // YYYY-MM-DDTHH:mm
    return (
      <label className="record-field-card">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <input
          name={field.name}
          type="datetime-local"
          value={dateValue}
          disabled={field.type === "autodate"}
          onChange={(event) => {
            if (event.target.value) {
              const date = new Date(event.target.value);
              // PocketBase uses string formats for dates. Using ISO string.
              onChange(date.toISOString().replace('T', ' '));
            } else {
              onChange(null);
            }
          }}
        />
      </label>
    );
  }

  if (field.type === "select") {
    const isMultiple = maxFiles(field) > 1;
    const options = selectFieldOptions(field);

    if (isMultiple) {
       const selectedValues = Array.isArray(value) ? value : (value ? [String(value)] : []);
       return (
          <label className="record-field-card wide">
            <span>
              <strong>{field.name}</strong>
              {commonMeta}
            </span>
            <div className="select-multiple-grid">
               {options.map((opt) => (
                 <label key={opt} className="check-row">
                   <input
                     type="checkbox"
                     checked={selectedValues.includes(opt)}
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
               ))}
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
          <option value="">-- Select --</option>
          {options.map(opt => <option key={opt} value={opt}>{opt}</option>)}
        </select>
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
