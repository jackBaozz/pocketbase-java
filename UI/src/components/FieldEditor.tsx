import { useState } from "react";
import { Edit3, Check, X, Trash2 } from "lucide-react";

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
};

type FieldEditorProps = {
  field: FieldSchema;
  index: number;
  onUpdate: (index: number, updatedField: FieldSchema) => void;
  onRemove: (index: number) => void;
};

export function FieldEditor({ field, index, onUpdate, onRemove }: FieldEditorProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [editState, setEditState] = useState<FieldSchema>(field);

  function handleSave() {
    onUpdate(index, editState);
    setIsEditing(false);
  }

  function handleCancel() {
    setEditState(field);
    setIsEditing(false);
  }

  if (isEditing) {
    return (
      <article className="field-builder-row editing">
        <div className="field-edit-form">
          <div className="field-edit-row">
            <label>
              Name
              <input
                type="text"
                value={editState.name}
                onChange={(e) => setEditState({ ...editState, name: e.target.value })}
                disabled={field.system}
              />
            </label>
            <label>
              Type
              <select
                value={editState.type}
                onChange={(e) => setEditState({ ...editState, type: e.target.value })}
                disabled={field.system}
              >
                {["text", "number", "bool", "email", "url", "date", "autodate", "select", "json", "file", "relation", "editor", "password"].map((t) => (
                  <option key={t} value={t}>{t}</option>
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
              Required
            </label>
            <label className="check-row">
              <input 
                type="checkbox" 
                checked={editState.unique ?? false} 
                onChange={(e) => setEditState({ ...editState, unique: e.target.checked })}
              />
              Unique
            </label>
            <label className="check-row">
              <input 
                type="checkbox" 
                checked={editState.hidden ?? false} 
                onChange={(e) => setEditState({ ...editState, hidden: e.target.checked })}
              />
              Hidden
            </label>
            <label className="check-row">
              <input 
                type="checkbox" 
                checked={editState.presentable ?? false} 
                onChange={(e) => setEditState({ ...editState, presentable: e.target.checked })}
              />
              Presentable
            </label>
          </div>
          
          {/* Type specific options could go here */}
          {editState.type === 'file' && (
             <div className="field-type-specific-options">
               <label>Max size (bytes)
                 <input type="number" value={editState.maxSize || ''} onChange={(e) => setEditState({...editState, maxSize: parseInt(e.target.value) || undefined})} />
               </label>
             </div>
          )}

          <div className="field-edit-actions">
            <button type="button" className="primary" onClick={handleSave}>
              <Check size={14} /> Save
            </button>
            <button type="button" className="subtle" onClick={handleCancel}>
              <X size={14} /> Cancel
            </button>
          </div>
        </div>
      </article>
    );
  }

  return (
    <article className="field-builder-row" key={`${field.name}-${index}`}>
      <div>
        <strong>{field.name || "(unnamed)"}</strong>
        <span>{field.type || "unknown"}</span>
      </div>
      <div className="chips">
        {field.required && <span>required</span>}
        {field.unique && <span>unique</span>}
        {field.hidden && <span>hidden</span>}
        {field.system && <span>system</span>}
      </div>
      <div className="field-row-actions">
        <button
          className="icon-button"
          type="button"
          onClick={() => setIsEditing(true)}
          title="Edit field"
          aria-label="Edit field"
        >
          <Edit3 size={15} />
        </button>
        <button
          className="icon-button danger"
          type="button"
          onClick={() => onRemove(index)}
          title="Remove field"
          aria-label="Remove field"
          disabled={field.system}
        >
          <Trash2 size={15} />
        </button>
      </div>
    </article>
  );
}
