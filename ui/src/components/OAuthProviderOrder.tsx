import { useState } from "react";
import { ChevronDown, ChevronUp, GripVertical } from "lucide-react";
import { useTranslation } from "react-i18next";
import { reorderOAuthProviders } from "../domain/oauthProviders";
import "./OAuthProviderOrder.css";

type Props = { names: string[]; labels: Record<string, string>; onChange: (names: string[]) => void };

export function OAuthProviderOrder({ names, labels, onChange }: Props) {
  const { t } = useTranslation();
  const [source, setSource] = useState<string | null>(null);
  return (
    <ol className="oauth-provider-order">
      {names.map((name, index) => (
        <li key={name} data-provider={name} draggable
          onDragStart={(event) => {
            setSource(name);
            event.dataTransfer.effectAllowed = "move";
            event.dataTransfer.setData("text/plain", name);
          }}
          onDragOver={(event) => { if (source) event.preventDefault(); }}
          onDragEnd={() => setSource(null)}
          onDrop={(event) => {
            event.preventDefault();
            if (source) onChange(reorderOAuthProviders(names, source, name));
            setSource(null);
          }}>
          <GripVertical size={16} aria-hidden="true" />
          <span>{labels[name] ?? name}</span>
          <button type="button" className="icon-button" disabled={index === 0}
            aria-label={t("actions.move_up", "Move up")} title={t("actions.move_up", "Move up")}
            onClick={() => onChange(reorderOAuthProviders(names, name, names[index - 1]))}>
            <ChevronUp size={16} />
          </button>
          <button type="button" className="icon-button" disabled={index === names.length - 1}
            aria-label={t("actions.move_down", "Move down")} title={t("actions.move_down", "Move down")}
            onClick={() => onChange(reorderOAuthProviders(names, name, names[index + 1]))}>
            <ChevronDown size={16} />
          </button>
        </li>
      ))}
    </ol>
  );
}
