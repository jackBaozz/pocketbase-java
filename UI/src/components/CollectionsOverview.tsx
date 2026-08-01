import { Database, GitBranch, Shield, X } from "lucide-react";
import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useModalInteraction } from "./useModalInteraction";
import "./CollectionsOverview.css";

type Field = {
  name: string;
  type: string;
  collectionId?: string;
  hidden?: boolean;
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
};

type CollectionsOverviewProps = {
  collections: OverviewCollection[];
  onClose: () => void;
  onSelect: (name: string) => void;
};

function ruleKind(value: string | null | undefined) {
  if (value === null) return "locked";
  if (value === "") return "public";
  return "custom";
}

export function CollectionsOverview({ collections, onClose, onSelect }: CollectionsOverviewProps) {
  const { t } = useTranslation();
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction(onClose);
  const byId = useMemo(() => new Map(collections.map((collection) => [collection.id, collection])), [collections]);
  const relations = useMemo(
    () =>
      collections.flatMap((collection) =>
        (collection.fields ?? [])
          .filter((field) => field.type === "relation" && field.collectionId)
          .map((field) => ({ source: collection, field, target: byId.get(field.collectionId ?? "") }))
      ),
    [byId, collections]
  );
  const ruleColumns = ["listRule", "viewRule", "createRule", "updateRule", "deleteRule"] as const;

  return (
    <div
      className="collections-overview-backdrop"
      role="presentation"
      onMouseDown={onBackdropMouseDown}
      onMouseUp={onBackdropMouseUp}
    >
      <section
        ref={dialogRef}
        className="collections-overview"
        role="dialog"
        aria-modal="true"
        aria-label={t("parity.collection.overview", "Collections overview")}
        tabIndex={-1}
      >
        <header className="collections-overview-head">
          <div>
            <p className="eyebrow">{t("parity.collection.overview_eyebrow", "Schema map")}</p>
            <h2>{t("parity.collection.overview", "Collections overview")}</h2>
          </div>
          <button className="icon-button" type="button" onClick={onClose} title={t("actions.close", "Close")} aria-label={t("actions.close", "Close")}>
            <X size={18} />
          </button>
        </header>

        <section className="collections-overview-graph" aria-label={t("parity.collection.erd", "Entity relationship diagram")}>
          {collections.map((collection) => {
            const Icon = collection.type === "auth" ? Shield : Database;
            const outgoing = relations.filter((relation) => relation.source.id === collection.id);
            return (
              <article className="overview-collection-card" key={collection.id}>
                <button type="button" className="overview-collection-title" onClick={() => onSelect(collection.name)}>
                  <Icon size={16} />
                  <strong>{collection.name}</strong>
                  <em>{collection.type}</em>
                </button>
                <ul>
                  {(collection.fields ?? []).filter((field) => !field.hidden).slice(0, 7).map((field) => (
                    <li key={field.name} className={field.type === "relation" ? "is-relation" : ""}>
                      <span>{field.name}</span>
                      <small>{field.type}</small>
                    </li>
                  ))}
                </ul>
                {outgoing.length > 0 && (
                  <div className="overview-relations">
                    {outgoing.map((relation) => (
                      <button type="button" key={relation.field.name} onClick={() => relation.target && onSelect(relation.target.name)} disabled={!relation.target}>
                        <GitBranch size={13} />
                        {relation.field.name} → {relation.target?.name ?? t("parity.collection.missing_target", "missing target")}
                      </button>
                    ))}
                  </div>
                )}
              </article>
            );
          })}
        </section>

        <section className="collections-overview-rules">
          <h3>{t("parity.collection.rules_overview", "Rules overview")}</h3>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("common.collection", "Collection")}</th>
                  {ruleColumns.map((key) => <th key={key}>{key.replace("Rule", "")}</th>)}
                </tr>
              </thead>
              <tbody>
                {collections.map((collection) => (
                  <tr key={`rules-${collection.id}`}>
                    <td><button type="button" onClick={() => onSelect(collection.name)}>{collection.name}</button></td>
                    {ruleColumns.map((key) => <td key={key}><span className={`overview-rule ${ruleKind(collection[key])}`}>{ruleKind(collection[key])}</span></td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </section>
    </div>
  );
}
