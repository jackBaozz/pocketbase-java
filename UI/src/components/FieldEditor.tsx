import { useState } from "react";
import { Edit3, Check, X, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

// Types derived from App.tsx
type FieldSchema = {
  id?: string;
  name: string;
  type: string;
  required?: boolean;
  /** @deprecated removed in PocketBase v0.23 - uniqueness is expressed through indexes. */
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
  // Per-type schema options (flattened field schema, PocketBase v0.23+).
  min?: number | string;
  max?: number | string;
  pattern?: string;
  autogeneratePattern?: string;
  onlyInt?: boolean;
  onlyDomains?: string[];
  exceptDomains?: string[];
  onCreate?: boolean;
  onUpdate?: boolean;
  values?: string[];
  cascadeDelete?: boolean;
  convertURLs?: boolean;
  cost?: number;
};

type CollectionOption = {
  id: string;
  name: string;
  type: string;
};

type FieldEditorProps = {
  field: FieldSchema;
  index: number;
  onUpdate: (index: number, updatedField: FieldSchema) => void;
  onRemove: (index: number) => void;
  /** Optional target list for `relation` fields. Falls back to a raw collectionId input. */
  collections?: CollectionOption[];
};

/**
 * Types offered by the type dropdown.
 * `password` is deliberately excluded (it only exists as an auth system field),
 * but an existing password field still renders and edits its own options.
 */
const SELECTABLE_TYPES = [
  "text",
  "number",
  "bool",
  "email",
  "url",
  "date",
  "autodate",
  "select",
  "file",
  "relation",
  "json",
  "editor",
  "geoPoint",
];

/** Options owned by each type - everything else is dropped when the type changes. */
const TYPE_OPTION_KEYS: Record<string, string[]> = {
  text: ["min", "max", "pattern", "autogeneratePattern"],
  number: ["min", "max", "onlyInt"],
  bool: [],
  email: ["onlyDomains", "exceptDomains"],
  url: ["onlyDomains", "exceptDomains"],
  date: ["min", "max"],
  autodate: ["onCreate", "onUpdate"],
  select: ["values", "maxSelect"],
  relation: ["collectionId", "cascadeDelete", "minSelect", "maxSelect"],
  file: ["mimeTypes", "thumbs", "maxSelect", "maxSize", "protected"],
  json: ["maxSize"],
  editor: ["convertURLs", "maxSize"],
  password: ["min", "max", "pattern", "cost"],
  geoPoint: [],
};

const COMMON_KEYS = ["id", "name", "type", "required", "hidden", "system", "presentable"];

const MIME_TYPE_PRESETS = [
  {
    key: "images",
    label: "Images (jpg, png, svg, gif, webp)",
    mimeTypes: ["image/jpeg", "image/png", "image/svg+xml", "image/gif", "image/webp"],
  },
  {
    key: "documents",
    label: "Documents (pdf, doc/docx, xls/xlsx)",
    mimeTypes: [
      "application/pdf",
      "application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.ms-excel",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ],
  },
  {
    key: "videos",
    label: "Videos (mp4, mpeg, avi, mov, 3gp)",
    mimeTypes: ["video/mp4", "video/mpeg", "video/x-msvideo", "video/quicktime", "video/3gpp"],
  },
  {
    key: "archives",
    label: "Archives (zip, 7zip, rar)",
    mimeTypes: ["application/zip", "application/x-7z-compressed", "application/x-rar-compressed"],
  },
];

/** Empty input -> `undefined` so the property is omitted from the payload instead of sending NaN/0. */
function toNumberOrUndefined(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === "") {
    return undefined;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function numberInputValue(value: number | string | undefined): string {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : "";
}

function splitNonEmpty(raw: string, separator = ","): string[] {
  return raw
    .split(separator)
    .map((item) => item.trim())
    .filter((item) => item !== "");
}

function joinNonEmpty(items: string[] | undefined, separator = ", "): string {
  return (items ?? [])
    .map((item) => item.trim())
    .filter((item) => item !== "")
    .join(separator);
}

/** RFC3339 (`YYYY-MM-DD HH:mm:ss.sssZ`) -> `datetime-local` value in the viewer timezone. */
function toDatetimeLocalValue(value: number | string | undefined): string {
  if (typeof value !== "string" || value.trim() === "") {
    return "";
  }
  const date = new Date(value.replaceAll(" ", "T"));
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const pad = (part: number) => String(part).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/** `datetime-local` value -> RFC3339 UTC string, matching the official `toRFC3339Datetime`. */
function fromDatetimeLocalValue(raw: string): string | undefined {
  if (!raw) {
    return undefined;
  }
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) {
    return undefined;
  }
  return date.toISOString().replace("T", " ");
}

function dedupeValues(values: string[] | undefined): string[] {
  return Array.from(new Set((values ?? []).filter((value) => value !== "")));
}

function isPresetActive(selected: string[] | undefined, preset: string[]): boolean {
  const current = selected ?? [];
  return preset.every((mimeType) => current.includes(mimeType));
}

function togglePreset(selected: string[] | undefined, preset: string[]): string[] {
  const current = selected ?? [];
  if (isPresetActive(current, preset)) {
    return current.filter((mimeType) => !preset.includes(mimeType));
  }
  return current.concat(preset.filter((mimeType) => !current.includes(mimeType)));
}

function autodateModeOf(field: FieldSchema): "create" | "update" | "createUpdate" {
  if (field.onCreate && field.onUpdate) {
    return "createUpdate";
  }
  if (field.onUpdate) {
    return "update";
  }
  return "create";
}

/** Keeps the shared props plus the options owned by the new type, so stale options never leak. */
function applyTypeChange(current: FieldSchema, nextType: string): FieldSchema {
  const allowed = new Set(COMMON_KEYS.concat(TYPE_OPTION_KEYS[nextType] ?? []));
  const source = current as Record<string, unknown>;
  const next: Record<string, unknown> = {};

  for (const key of Object.keys(source)) {
    if (allowed.has(key)) {
      next[key] = source[key];
    }
  }
  next.type = nextType;

  if (nextType === "autodate" && next.onCreate === undefined && next.onUpdate === undefined) {
    next.onCreate = true;
    next.onUpdate = false;
  }

  return next as FieldSchema;
}

export function FieldEditor({ field, index, onUpdate, onRemove, collections }: FieldEditorProps) {
  const { t } = useTranslation();
  const [isEditing, setIsEditing] = useState(false);
  const [editState, setEditState] = useState<FieldSchema>(field);

  function patch(changes: Partial<FieldSchema>) {
    setEditState((previous) => ({ ...previous, ...changes }));
  }

  function handleSave() {
    const next: FieldSchema = { ...editState };
    if (next.type === "select") {
      next.values = dedupeValues(next.values);
    }
    if (next.type === "autodate") {
      // persist exactly what the "Auto set on" dropdown shows (official inits this on mount)
      const mode = autodateModeOf(next);
      next.onCreate = mode !== "update";
      next.onUpdate = mode !== "create";
    }
    // `unique` was dropped in v0.23 (uniqueness lives in collection indexes now).
    delete next.unique;
    onUpdate(index, next);
    setIsEditing(false);
  }

  function handleCancel() {
    setEditState(field);
    setIsEditing(false);
  }

  const typeOptions =
    editState.type && !SELECTABLE_TYPES.includes(editState.type)
      ? SELECTABLE_TYPES.concat(editState.type)
      : SELECTABLE_TYPES;

  const isMultiple = (editState.maxSelect ?? 1) > 1;

  const relationTargets = (collections ?? [])
    .filter((collection) => collection.type !== "view")
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name));

  function renderSelectMode(multipleFallback: number) {
    return (
      <label>
        {t("fields.select_mode", "Select mode")}
        <select
          value={isMultiple ? "multiple" : "single"}
          onChange={(e) =>
            patch({ maxSelect: e.target.value === "multiple" ? Math.max(multipleFallback, 2) : 1 })
          }
        >
          <option value="single">{t("fields.single", "Single")}</option>
          <option value="multiple">{t("fields.multiple", "Multiple")}</option>
        </select>
      </label>
    );
  }

  function renderDomainOptions() {
    const hasOnly = (editState.onlyDomains ?? []).length > 0;
    const hasExcept = (editState.exceptDomains ?? []).length > 0;
    return (
      <div className="field-options-grid">
        <label>
          {t("fields.only_domains", "Only domains")}
          <input
            type="text"
            placeholder={t("fields.domains_placeholder", "e.g. example.com, test.dev")}
            defaultValue={joinNonEmpty(editState.onlyDomains)}
            disabled={hasExcept}
            key={`onlyDomains-${joinNonEmpty(editState.onlyDomains)}`}
            onBlur={(e) => patch({ onlyDomains: splitNonEmpty(e.target.value) })}
          />
        </label>
        <label>
          {t("fields.except_domains", "Except domains")}
          <input
            type="text"
            placeholder={t("fields.domains_placeholder", "e.g. example.com, test.dev")}
            defaultValue={joinNonEmpty(editState.exceptDomains)}
            disabled={hasOnly}
            key={`exceptDomains-${joinNonEmpty(editState.exceptDomains)}`}
            onBlur={(e) => patch({ exceptDomains: splitNonEmpty(e.target.value) })}
          />
        </label>
        <p className="field-option-help span-2">
          {t(
            "fields.domains_help",
            "Use comma as separator. Only one of the two lists can be set at a time.",
          )}
        </p>
      </div>
    );
  }

  function renderTypeOptions() {
    switch (editState.type) {
      case "text":
        return (
          <div className="field-options-grid">
            <label>
              {t("fields.min_length", "Min length")}
              <input
                type="number"
                min={0}
                step={1}
                placeholder={t("fields.no_min_limit", "No min limit")}
                value={numberInputValue(editState.min)}
                onChange={(e) => patch({ min: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label>
              {t("fields.max_length", "Max length")}
              <input
                type="number"
                min={typeof editState.min === "number" ? editState.min : 0}
                step={1}
                placeholder={t("fields.default_max_text", "Default to max 5000 characters")}
                value={numberInputValue(editState.max)}
                onChange={(e) => patch({ max: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label>
              {t("fields.validation_pattern", "Validation pattern")}
              <input
                type="text"
                placeholder="^[a-z0-9]+$"
                value={editState.pattern ?? ""}
                onChange={(e) => patch({ pattern: e.target.value })}
              />
            </label>
            <label>
              {t("fields.autogenerate_pattern", "Autogenerate pattern")}
              <input
                type="text"
                placeholder="[a-z0-9]{30}"
                value={editState.autogeneratePattern ?? ""}
                onChange={(e) => patch({ autogeneratePattern: e.target.value })}
              />
            </label>
            <p className="field-option-help span-2">
              {t(
                "fields.autogenerate_pattern_help",
                "Autogenerate a value matching the pattern when the create request omits the field.",
              )}
            </p>
          </div>
        );

      case "number":
        return (
          <div className="field-options-grid">
            <label>
              {t("fields.min", "Min")}
              <input
                type="number"
                placeholder={t("fields.no_min_limit", "No min limit")}
                value={numberInputValue(editState.min)}
                onChange={(e) => patch({ min: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label>
              {t("fields.max", "Max")}
              <input
                type="number"
                min={typeof editState.min === "number" ? editState.min : undefined}
                placeholder={t("fields.no_max_limit", "No max limit")}
                value={numberInputValue(editState.max)}
                onChange={(e) => patch({ max: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label className="check-row span-2">
              <input
                type="checkbox"
                checked={editState.onlyInt ?? false}
                onChange={(e) => patch({ onlyInt: e.target.checked })}
              />
              {t("fields.only_int", "No decimals")}
            </label>
          </div>
        );

      case "email":
      case "url":
        return renderDomainOptions();

      case "date":
        return (
          <div className="field-options-grid">
            <label>
              {t("fields.min_date", "Min date (local)")}
              <input
                type="datetime-local"
                step={1}
                value={toDatetimeLocalValue(editState.min)}
                onChange={(e) => patch({ min: fromDatetimeLocalValue(e.target.value) })}
              />
            </label>
            <label>
              {t("fields.max_date", "Max date (local)")}
              <input
                type="datetime-local"
                step={1}
                value={toDatetimeLocalValue(editState.max)}
                onChange={(e) => patch({ max: fromDatetimeLocalValue(e.target.value) })}
              />
            </label>
          </div>
        );

      case "autodate":
        return (
          <div className="field-options-grid">
            <label className="span-2">
              {t("fields.auto_set_on", "Auto set on")}
              <select
                value={autodateModeOf(editState)}
                onChange={(e) =>
                  patch({
                    onCreate: e.target.value !== "update",
                    onUpdate: e.target.value !== "create",
                  })
                }
              >
                <option value="create">{t("fields.autodate_create", "Create")}</option>
                <option value="update">{t("fields.autodate_update", "Update")}</option>
                <option value="createUpdate">
                  {t("fields.autodate_create_update", "Create/Update")}
                </option>
              </select>
            </label>
          </div>
        );

      case "select": {
        const values = editState.values ?? [];
        return (
          <div className="field-options-grid">
            <label className="span-2">
              {t("fields.choices", "Choices")}
              <textarea
                className="field-choices-input"
                rows={4}
                placeholder={t("fields.choices_placeholder", "One choice per line")}
                value={values.join("\n")}
                onChange={(e) => patch({ values: e.target.value.split("\n") })}
                onBlur={(e) => patch({ values: dedupeValues(e.target.value.split("\n")) })}
              />
            </label>
            <p className="field-option-help span-2">
              {t(
                "fields.choices_help",
                "New-line separated choices. Duplicates and empty lines are removed automatically.",
              )}
            </p>
            {renderSelectMode(dedupeValues(values).length || 2)}
            {isMultiple && (
              <label>
                {t("fields.max_select", "Max select")}
                <input
                  type="number"
                  min={2}
                  step={1}
                  max={dedupeValues(values).length || undefined}
                  placeholder={t("fields.default_to_single", "Default to single")}
                  value={numberInputValue(editState.maxSelect)}
                  onChange={(e) => patch({ maxSelect: toNumberOrUndefined(e.target.value) })}
                />
              </label>
            )}
          </div>
        );
      }

      case "relation": {
        const targetLocked = Boolean(field.id);
        const knownTarget =
          !editState.collectionId ||
          relationTargets.some((collection) => collection.id === editState.collectionId);
        return (
          <div className="field-options-grid">
            <label>
              {t("fields.related_collection", "Related collection")}
              {collections ? (
                <select
                  value={editState.collectionId ?? ""}
                  disabled={targetLocked}
                  onChange={(e) => patch({ collectionId: e.target.value })}
                >
                  <option value="">{t("fields.select_collection", "Select collection")}</option>
                  {!knownTarget && (
                    <option value={editState.collectionId}>{editState.collectionId}</option>
                  )}
                  {relationTargets.map((collection) => (
                    <option key={collection.id} value={collection.id}>
                      {collection.name}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  type="text"
                  placeholder={t("fields.collection_id_placeholder", "Target collection id")}
                  value={editState.collectionId ?? ""}
                  disabled={targetLocked}
                  onChange={(e) => patch({ collectionId: e.target.value })}
                />
              )}
            </label>
            {renderSelectMode(10)}
            {targetLocked && (
              <p className="field-option-help span-2">
                {t(
                  "fields.relation_target_locked",
                  "The related collection cannot be changed once the field exists.",
                )}
              </p>
            )}
            {isMultiple && (
              <>
                <label>
                  {t("fields.min_select", "Min select")}
                  <input
                    type="number"
                    min={0}
                    step={1}
                    placeholder={t("fields.no_min_limit", "No min limit")}
                    value={numberInputValue(editState.minSelect)}
                    onChange={(e) => patch({ minSelect: toNumberOrUndefined(e.target.value) })}
                  />
                </label>
                <label>
                  {t("fields.max_select", "Max select")}
                  <input
                    type="number"
                    min={editState.minSelect || 2}
                    step={1}
                    placeholder={t("fields.default_to_single", "Default to single")}
                    value={numberInputValue(editState.maxSelect)}
                    onChange={(e) => patch({ maxSelect: toNumberOrUndefined(e.target.value) })}
                  />
                </label>
              </>
            )}
            <label className="check-row span-2">
              <input
                type="checkbox"
                checked={editState.cascadeDelete ?? false}
                onChange={(e) => patch({ cascadeDelete: e.target.checked })}
              />
              {t("fields.cascade_delete", "Cascade delete")}
            </label>
          </div>
        );
      }

      case "file":
        return (
          <div className="field-options-grid">
            <label className="span-2">
              {t("fields.mime_types", "Allowed mime types")}
              <input
                type="text"
                placeholder={t("fields.no_restriction", "No restriction")}
                defaultValue={joinNonEmpty(editState.mimeTypes)}
                key={`mimeTypes-${joinNonEmpty(editState.mimeTypes)}`}
                onBlur={(e) => patch({ mimeTypes: splitNonEmpty(e.target.value) })}
              />
            </label>
            <div className="span-2">
              <div className="field-preset-row">
                {MIME_TYPE_PRESETS.map((preset) => (
                  <button
                    key={preset.key}
                    type="button"
                    className={`field-preset-chip${
                      isPresetActive(editState.mimeTypes, preset.mimeTypes) ? " active" : ""
                    }`}
                    aria-pressed={isPresetActive(editState.mimeTypes, preset.mimeTypes)}
                    onClick={() =>
                      patch({ mimeTypes: togglePreset(editState.mimeTypes, preset.mimeTypes) })
                    }
                  >
                    {t(`fields.mime_preset_${preset.key}`, preset.label)}
                  </button>
                ))}
              </div>
              <p className="field-option-help">
                {t(
                  "fields.mime_types_help",
                  "Use comma as separator, or toggle the presets above. Leave empty for no restriction.",
                )}
              </p>
            </div>
            <label className="span-2">
              {t("fields.thumbs", "Thumb sizes")}
              <input
                type="text"
                placeholder={t("fields.thumbs_placeholder", "e.g. 100x100, 0x200, 100x100t")}
                defaultValue={joinNonEmpty(editState.thumbs)}
                key={`thumbs-${joinNonEmpty(editState.thumbs)}`}
                onBlur={(e) => patch({ thumbs: splitNonEmpty(e.target.value) })}
              />
            </label>
            <p className="field-option-help span-2">
              {t(
                "fields.thumbs_help",
                "Use comma as separator. WxH crops from center, WxHt from top, WxHb from bottom, WxHf fits inside, 0xH or Wx0 preserves the aspect ratio.",
              )}
            </p>
            {renderSelectMode(10)}
            {isMultiple && (
              <label>
                {t("fields.max_select", "Max select")}
                <input
                  type="number"
                  min={2}
                  step={1}
                  placeholder={t("fields.default_to_single", "Default to single")}
                  value={numberInputValue(editState.maxSelect)}
                  onChange={(e) => patch({ maxSelect: toNumberOrUndefined(e.target.value) })}
                />
              </label>
            )}
            <label>
              {t("fields.max_size_bytes", "Max size (bytes)")}
              <input
                type="number"
                min={0}
                step={1}
                placeholder={t("fields.default_max_file_size", "~5MB default")}
                value={numberInputValue(editState.maxSize)}
                onChange={(e) => patch({ maxSize: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label className="check-row span-2">
              <input
                type="checkbox"
                checked={editState.protected ?? false}
                onChange={(e) => patch({ protected: e.target.checked })}
              />
              {t("fields.protected", "Protected")}
            </label>
            <p className="field-option-help span-2">
              {t(
                "fields.protected_help",
                "File download requests will need to satisfy the View API rule.",
              )}
            </p>
          </div>
        );

      case "json":
        return (
          <div className="field-options-grid">
            <label className="span-2">
              {t("fields.max_size_bytes", "Max size (bytes)")}
              <input
                type="number"
                min={0}
                step={1}
                placeholder={t("fields.default_max_json_size", "Default to max ~1MB")}
                value={numberInputValue(editState.maxSize)}
                onChange={(e) => patch({ maxSize: toNumberOrUndefined(e.target.value) })}
              />
            </label>
          </div>
        );

      case "editor":
        return (
          <div className="field-options-grid">
            <label className="span-2">
              {t("fields.max_size_bytes", "Max size (bytes)")}
              <input
                type="number"
                min={0}
                step={1}
                placeholder={t("fields.default_max_editor_size", "Default to max ~5MB")}
                value={numberInputValue(editState.maxSize)}
                onChange={(e) => patch({ maxSize: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label className="check-row span-2">
              <input
                type="checkbox"
                checked={editState.convertURLs ?? false}
                onChange={(e) => patch({ convertURLs: e.target.checked })}
              />
              {t("fields.convert_urls", "Strip URLs domain")}
            </label>
          </div>
        );

      case "password":
        return (
          <div className="field-options-grid">
            <label>
              {t("fields.min_length", "Min length")}
              <input
                type="number"
                min={0}
                max={71}
                step={1}
                placeholder={t("fields.no_min_limit", "No min limit")}
                value={numberInputValue(editState.min)}
                onChange={(e) => patch({ min: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label>
              {t("fields.max_length", "Max length")}
              <input
                type="number"
                min={typeof editState.min === "number" ? editState.min : 0}
                max={71}
                step={1}
                placeholder={t("fields.default_max_password", "Up to 71 chars")}
                value={numberInputValue(editState.max)}
                onChange={(e) => patch({ max: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label>
              {t("fields.bcrypt_cost", "Bcrypt cost")}
              <input
                type="number"
                min={4}
                max={31}
                step={1}
                placeholder={t("fields.default_bcrypt_cost", "Default to 10")}
                value={numberInputValue(editState.cost)}
                onChange={(e) => patch({ cost: toNumberOrUndefined(e.target.value) })}
              />
            </label>
            <label>
              {t("fields.validation_pattern", "Validation pattern")}
              <input
                type="text"
                placeholder="^\w+$"
                value={editState.pattern ?? ""}
                onChange={(e) => patch({ pattern: e.target.value })}
              />
            </label>
          </div>
        );

      default:
        // bool / geoPoint (and unknown types) have no extra schema options.
        return null;
    }
  }

  if (isEditing) {
    const typeOptionsBlock = renderTypeOptions();

    return (
      <article className="field-builder-row editing">
        <div className="field-edit-form">
          <div className="field-edit-row">
            <label>
              {t("common.name", "Name")}
              <input
                type="text"
                value={editState.name}
                onChange={(e) => setEditState({ ...editState, name: e.target.value })}
                disabled={field.system}
              />
            </label>
            <label>
              {t("common.type", "Type")}
              <select
                value={editState.type}
                onChange={(e) => setEditState(applyTypeChange(editState, e.target.value))}
                disabled={field.system}
              >
                {typeOptions.map((typeName) => (
                  <option key={typeName} value={typeName}>
                    {typeName}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="field-edit-options check-row-group">
            <label className="check-row">
              <input
                type="checkbox"
                checked={editState.required ?? false}
                onChange={(e) => setEditState({ ...editState, required: e.target.checked })}
              />
              {t("collections.required", "Required")}
            </label>
            <label className="check-row">
              <input
                type="checkbox"
                checked={editState.hidden ?? false}
                onChange={(e) =>
                  setEditState({
                    ...editState,
                    hidden: e.target.checked,
                    // a hidden field can never be presentable (official fieldSettings behavior)
                    presentable: e.target.checked ? false : editState.presentable,
                  })
                }
              />
              {t("collections.hidden", "Hidden")}
            </label>
            <label className="check-row">
              <input
                type="checkbox"
                checked={editState.presentable ?? false}
                disabled={editState.hidden ?? false}
                onChange={(e) => setEditState({ ...editState, presentable: e.target.checked })}
              />
              {t("fields.presentable", "Presentable")}
            </label>
          </div>

          {typeOptionsBlock && (
            <div className="field-type-specific-options">{typeOptionsBlock}</div>
          )}

          <div className="field-edit-actions">
            <button type="button" className="primary" onClick={handleSave}>
              <Check size={14} /> {t("actions.save", "Save")}
            </button>
            <button type="button" className="subtle" onClick={handleCancel}>
              <X size={14} /> {t("actions.cancel", "Cancel")}
            </button>
          </div>
        </div>
      </article>
    );
  }

  return (
    <article className="field-builder-row" key={`${field.name}-${index}`}>
      <div>
        <strong>{field.name || t("fields.unnamed", "(unnamed)")}</strong>
        <span>{field.type || t("fields.unknown", "unknown")}</span>
      </div>
      <div className="chips">
        {field.required && <span>{t("collections.required", "required")}</span>}
        {field.hidden && <span>{t("collections.hidden", "hidden")}</span>}
        {field.system && <span>{t("collections.system", "system")}</span>}
      </div>
      <div className="field-row-actions">
        <button
          className="icon-button"
          type="button"
          onClick={() => {
            setEditState(field);
            setIsEditing(true);
          }}
          title={t("fields.edit_field", "Edit field")}
          aria-label={t("fields.edit_field", "Edit field")}
        >
          <Edit3 size={15} />
        </button>
        <button
          className="icon-button danger"
          type="button"
          onClick={() => onRemove(index)}
          title={t("fields.remove_field", "Remove field")}
          aria-label={t("fields.remove_field", "Remove field")}
          disabled={field.system}
        >
          <Trash2 size={15} />
        </button>
      </div>
    </article>
  );
}
