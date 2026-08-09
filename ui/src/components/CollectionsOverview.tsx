import {
  AlignLeft,
  Braces,
  Calendar,
  CaseSensitive,
  Database,
  Eye,
  Hash,
  Image as ImageIcon,
  KeyRound,
  Link2,
  List,
  Mail,
  MapPin,
  Minus,
  Plus,
  ToggleLeft,
  Type,
  Users,
  X
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useMemo, useState } from "react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useModalInteraction } from "./useModalInteraction";
import "./CollectionsOverview.css";

/* -------------------------------------------------------------------------- */
/* types                                                                       */
/* -------------------------------------------------------------------------- */

type Field = {
  name: string;
  type: string;
  collectionId?: string;
  hidden?: boolean;
  maxSelect?: number;
};

export type OverviewCollection = {
  id: string;
  name: string;
  type: string;
  system?: boolean;
  fields?: Field[];
  listRule?: string | null;
  viewRule?: string | null;
  createRule?: string | null;
  updateRule?: string | null;
  deleteRule?: string | null;
  authRule?: string | null;
  manageRule?: string | null;
  mfa?: { enabled?: boolean; rule?: string | null };
};

type CollectionsOverviewProps = {
  collections: OverviewCollection[];
  onClose: () => void;
  onSelect: (name: string) => void;
};

type MainTab = "fields" | "rules";

type RuleKey =
  | "listRule"
  | "viewRule"
  | "createRule"
  | "updateRule"
  | "deleteRule"
  | "authRule"
  | "manageRule"
  | "mfaRule";

type CardPos = { x: number; y: number; w: number; h: number };

/* -------------------------------------------------------------------------- */
/* constants                                                                   */
/* -------------------------------------------------------------------------- */

const CARD_WIDTH = 230;
const FIELD_ROW_H = 28;
const CARD_HEADER_H = 36;
const CARD_PAD_Y = 0;
const GAP_X = 48;
const GAP_Y = 36;
const CANVAS_PAD = 48;

const FIELD_ICONS: Record<string, LucideIcon> = {
  text: CaseSensitive,
  editor: AlignLeft,
  number: Hash,
  bool: ToggleLeft,
  email: Mail,
  url: Link2,
  date: Calendar,
  autodate: Calendar,
  select: List,
  file: ImageIcon,
  relation: Link2,
  json: Braces,
  password: KeyRound,
  geoPoint: MapPin
};

const RULE_TABS: ReadonlyArray<{ key: RuleKey; labelKey: string; fallback: string }> = [
  { key: "listRule", labelKey: "collections.list_search_rule", fallback: "List/Search rule" },
  { key: "viewRule", labelKey: "collections.view_rule", fallback: "View rule" },
  { key: "createRule", labelKey: "collections.create_rule", fallback: "Create rule" },
  { key: "updateRule", labelKey: "collections.update_rule", fallback: "Update rule" },
  { key: "deleteRule", labelKey: "collections.delete_rule", fallback: "Delete rule" },
  { key: "authRule", labelKey: "parity.collection.auth_rule", fallback: "Auth rule" },
  { key: "manageRule", labelKey: "parity.collection.manage_rule", fallback: "Manage rule" },
  { key: "mfaRule", labelKey: "parity.collection.mfa_rule", fallback: "MFA rule" }
];

/* -------------------------------------------------------------------------- */
/* helpers                                                                     */
/* -------------------------------------------------------------------------- */

function isSystemCollection(collection: OverviewCollection): boolean {
  return collection.system === true || collection.name.startsWith("_");
}

function readRule(collection: OverviewCollection, key: RuleKey): string | null | undefined {
  if (key === "mfaRule") return collection.mfa?.rule;
  return collection[key];
}

function layoutCollections(collections: OverviewCollection[]): Map<string, CardPos> {
  const positions = new Map<string, CardPos>();
  const cols = Math.max(1, Math.min(4, collections.length));
  collections.forEach((collection, index) => {
    const col = index % cols;
    const row = Math.floor(index / cols);
    const fieldCount = Math.max(1, (collection.fields ?? []).length);
    const h = CARD_HEADER_H + CARD_PAD_Y + fieldCount * FIELD_ROW_H + 2;
    // Place each row based on max height of previous rows for tighter packing.
    let y = CANVAS_PAD;
    for (let r = 0; r < row; r++) {
      let rowMax = CARD_HEADER_H + FIELD_ROW_H;
      for (let c = 0; c < cols; c++) {
        const prev = collections[r * cols + c];
        if (!prev) continue;
        const prevH = CARD_HEADER_H + CARD_PAD_Y + Math.max(1, (prev.fields ?? []).length) * FIELD_ROW_H + 2;
        rowMax = Math.max(rowMax, prevH);
      }
      y += rowMax + GAP_Y;
    }
    positions.set(collection.id, {
      x: CANVAS_PAD + col * (CARD_WIDTH + GAP_X),
      y,
      w: CARD_WIDTH,
      h
    });
  });
  return positions;
}

function canvasSize(positions: Map<string, CardPos>): { width: number; height: number } {
  let maxX = 400;
  let maxY = 300;
  for (const pos of positions.values()) {
    maxX = Math.max(maxX, pos.x + pos.w + CANVAS_PAD);
    maxY = Math.max(maxY, pos.y + pos.h + CANVAS_PAD);
  }
  return { width: maxX, height: maxY };
}

function fieldIcon(type: string): LucideIcon {
  return FIELD_ICONS[type] ?? Type;
}

function collectionIcon(type: string): LucideIcon {
  if (type === "auth") return Users;
  if (type === "view") return Eye;
  return Database;
}

/* -------------------------------------------------------------------------- */
/* rule value cell                                                             */
/* -------------------------------------------------------------------------- */

function RuleValue({ value }: { value: string | null | undefined }): ReactNode {
  const { t } = useTranslation();
  // Official semantics: null = superusers only, "" = public, otherwise custom expression.
  if (value === null || value === undefined) {
    return (
      <span className="cov-rule-badge cov-rule-locked">
        {t("collections.rule_locked", "Superusers only")}
      </span>
    );
  }
  if (value === "") {
    return (
      <span className="cov-rule-badge cov-rule-public">
        {t("parity.collection.public", "Public")}
      </span>
    );
  }
  return (
    <code className="cov-rule-expr" title={value}>
      {highlightRule(value)}
    </code>
  );
}

/** Lightweight highlighter for rule expressions (strings / operators / idents). */
function highlightRule(rule: string): ReactNode {
  const parts = rule.split(/("(?:\\.|[^"])*"|'(?:\\.|[^'])*'|@[\w.]+|\btrue\b|\bfalse\b|&&|\|\||!=|<=|>=|[=<>!]+)/g);
  return parts.map((part, index) => {
    if (!part) return null;
    if (/^["']/.test(part)) return <span key={index} className="cov-tok-str">{part}</span>;
    if (part.startsWith("@")) return <span key={index} className="cov-tok-ref">{part}</span>;
    if (part === "true" || part === "false") return <span key={index} className="cov-tok-bool">{part}</span>;
    if (/^(&&|\|\||!=|<=|>=|[=<>!]+)$/.test(part)) return <span key={index} className="cov-tok-op">{part}</span>;
    return <span key={index}>{part}</span>;
  });
}

/* -------------------------------------------------------------------------- */
/* main component                                                              */
/* -------------------------------------------------------------------------- */

export function CollectionsOverview({ collections, onClose, onSelect }: CollectionsOverviewProps) {
  const { t } = useTranslation();
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction(onClose);
  const [mainTab, setMainTab] = useState<MainTab>("fields");
  const [ruleTab, setRuleTab] = useState<RuleKey>("listRule");
  const [showSystem, setShowSystem] = useState(false);
  const [scale, setScale] = useState(1);

  const visibleCollections = useMemo(
    () => collections.filter((collection) => showSystem || !isSystemCollection(collection)),
    [collections, showSystem]
  );

  // Prefer non-system first so the ERD reads like the official layout.
  const orderedCollections = useMemo(() => {
    const user = visibleCollections.filter((collection) => !isSystemCollection(collection));
    const system = visibleCollections.filter((collection) => isSystemCollection(collection));
    return [...user, ...system];
  }, [visibleCollections]);

  const byId = useMemo(
    () => new Map(collections.map((collection) => [collection.id, collection])),
    [collections]
  );

  const positions = useMemo(() => layoutCollections(orderedCollections), [orderedCollections]);
  const size = useMemo(() => canvasSize(positions), [positions]);

  const relations = useMemo(
    () =>
      orderedCollections.flatMap((collection) =>
        (collection.fields ?? [])
          .filter((field) => field.type === "relation" && field.collectionId)
          .map((field) => ({
            sourceId: collection.id,
            targetId: field.collectionId as string,
            fieldName: field.name,
            target: byId.get(field.collectionId as string)
          }))
          .filter((relation) => orderedCollections.some((c) => c.id === relation.targetId))
      ),
    [orderedCollections, byId]
  );

  function zoomIn() {
    setScale((value) => Math.min(1.6, Math.round((value + 0.1) * 10) / 10));
  }

  function zoomOut() {
    setScale((value) => Math.max(0.5, Math.round((value - 0.1) * 10) / 10));
  }

  return (
    <div
      className="cov-backdrop"
      role="presentation"
      onMouseDown={onBackdropMouseDown}
      onMouseUp={onBackdropMouseUp}
    >
      <section
        ref={dialogRef}
        className="cov-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t("parity.collection.overview", "Collections overview")}
        tabIndex={-1}
      >
        <header className="cov-head">
          <h2 className="cov-title">{t("parity.collection.overview", "Collections overview")}</h2>
          <div className="cov-head-actions">
            <label className="cov-toggle">
              <input
                type="checkbox"
                checked={showSystem}
                onChange={(event) => setShowSystem(event.target.checked)}
              />
              <span className="cov-toggle-track" aria-hidden="true" />
              <span className="cov-toggle-label">
                {t("parity.collection.system_collections", "System collections")}
              </span>
            </label>
            <button
              type="button"
              className="cov-close"
              onClick={onClose}
              title={t("actions.close", "Close")}
              aria-label={t("actions.close", "Close")}
            >
              <X size={18} />
            </button>
          </div>
        </header>

        <div className="cov-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mainTab === "fields"}
            className={`cov-tab${mainTab === "fields" ? " active" : ""}`}
            onClick={() => setMainTab("fields")}
          >
            {t("parity.collection.fields_and_relations", "Fields and relations")}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mainTab === "rules"}
            className={`cov-tab${mainTab === "rules" ? " active" : ""}`}
            onClick={() => setMainTab("rules")}
          >
            {t("parity.collection.rules_tab", "Rules")}
          </button>
        </div>

        {mainTab === "fields" ? (
          <div className="cov-fields-pane">
            <div className="cov-canvas-scroll">
              <div
                className="cov-canvas"
                style={{
                  width: size.width * scale,
                  height: size.height * scale
                }}
              >
                <div
                  className="cov-canvas-inner"
                  style={{
                    width: size.width,
                    height: size.height,
                    transform: `scale(${scale})`,
                    transformOrigin: "0 0"
                  }}
                >
                  <svg className="cov-edges" width={size.width} height={size.height} aria-hidden="true">
                    {relations.map((relation) => {
                      const from = positions.get(relation.sourceId);
                      const to = positions.get(relation.targetId);
                      if (!from || !to) return null;
                      const x1 = from.x + from.w;
                      const y1 = from.y + CARD_HEADER_H / 2 + 8;
                      const x2 = to.x;
                      const y2 = to.y + CARD_HEADER_H / 2 + 8;
                      const mid = (x1 + x2) / 2;
                      const d = `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`;
                      return (
                        <path
                          key={`${relation.sourceId}-${relation.fieldName}-${relation.targetId}`}
                          d={d}
                          className="cov-edge"
                          fill="none"
                          markerEnd="url(#cov-arrow)"
                        />
                      );
                    })}
                    <defs>
                      <marker
                        id="cov-arrow"
                        viewBox="0 0 10 10"
                        refX="8"
                        refY="5"
                        markerWidth="6"
                        markerHeight="6"
                        orient="auto-start-reverse"
                      >
                        <path d="M 0 0 L 10 5 L 0 10 z" className="cov-edge-arrow" />
                      </marker>
                    </defs>
                  </svg>

                  {orderedCollections.map((collection) => {
                    const pos = positions.get(collection.id);
                    if (!pos) return null;
                    const system = isSystemCollection(collection);
                    return (
                      <article
                        key={collection.id}
                        className={`cov-card${system ? " is-system" : ""}`}
                        style={{ left: pos.x, top: pos.y, width: pos.w }}
                      >
                        <button
                          type="button"
                          className="cov-card-head"
                          onClick={() => onSelect(collection.name)}
                          title={collection.name}
                        >
                          <span className="cov-card-name">{collection.name}</span>
                        </button>
                        <ul className="cov-fields">
                          {(collection.fields ?? []).map((field) => {
                            const Icon = fieldIcon(field.type);
                            const isRelation = field.type === "relation";
                            const multi = (field.maxSelect ?? 1) !== 1;
                            return (
                              <li
                                key={field.name}
                                className={`cov-field${isRelation ? " is-relation" : ""}${field.hidden ? " is-hidden" : ""}`}
                              >
                                <Icon size={13} className="cov-field-icon" aria-hidden="true" />
                                <span className="cov-field-name">{field.name}</span>
                                {field.hidden && (
                                  <span className="cov-field-badge cov-badge-hidden">
                                    {t("parity.collection.hidden_badge", "Hidden")}
                                  </span>
                                )}
                                {isRelation && (
                                  <span className="cov-field-meta">
                                    {multi
                                      ? t("parity.collection.multiple", "multiple")
                                      : t("parity.collection.single", "single")}
                                  </span>
                                )}
                              </li>
                            );
                          })}
                        </ul>
                      </article>
                    );
                  })}
                </div>
              </div>
            </div>

            <div className="cov-zoom">
              <button
                type="button"
                className="cov-zoom-btn"
                onClick={zoomIn}
                title={t("parity.collection.zoom_in", "Zoom in")}
                aria-label={t("parity.collection.zoom_in", "Zoom in")}
              >
                <Plus size={16} />
              </button>
              <button
                type="button"
                className="cov-zoom-btn"
                onClick={zoomOut}
                title={t("parity.collection.zoom_out", "Zoom out")}
                aria-label={t("parity.collection.zoom_out", "Zoom out")}
              >
                <Minus size={16} />
              </button>
            </div>
          </div>
        ) : (
          <div className="cov-rules-pane">
            <div className="cov-rule-tabs" role="tablist" aria-label={t("parity.collection.rules_tab", "Rules")}>
              {RULE_TABS.map((tab) => (
                <button
                  key={tab.key}
                  type="button"
                  role="tab"
                  aria-selected={ruleTab === tab.key}
                  className={`cov-rule-tab${ruleTab === tab.key ? " active" : ""}`}
                  onClick={() => setRuleTab(tab.key)}
                >
                  {t(tab.labelKey, tab.fallback)}
                </button>
              ))}
            </div>

            <div className="cov-rules-table-wrap">
              <table className="cov-rules-table">
                <tbody>
                  {orderedCollections.map((collection) => {
                    const Icon = collectionIcon(collection.type);
                    const value = readRule(collection, ruleTab);
                    // Auth/MFA rules only apply to auth collections — show empty dash for others on those tabs.
                    const applicable =
                      ruleTab === "authRule" || ruleTab === "mfaRule" || ruleTab === "manageRule"
                        ? collection.type === "auth" || ruleTab === "manageRule"
                        : true;
                    // manageRule exists on all collection types in PB
                    return (
                      <tr key={collection.id}>
                        <th scope="row">
                          <button
                            type="button"
                            className="cov-rule-collection"
                            onClick={() => onSelect(collection.name)}
                          >
                            <Icon size={15} aria-hidden="true" />
                            <span>{collection.name}</span>
                          </button>
                        </th>
                        <td>
                          {applicable ? (
                            <RuleValue value={value} />
                          ) : (
                            <span className="cov-rule-na">—</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

export default CollectionsOverview;
