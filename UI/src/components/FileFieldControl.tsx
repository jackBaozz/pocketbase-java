import { useEffect, useMemo, useState } from "react";
import {
  Download,
  Eye,
  FileText,
  GripVertical,
  Image as ImageIcon,
  Music2,
  RotateCcw,
  Trash2,
  Upload,
  Video,
  X
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { useModalInteraction } from "./useModalInteraction";
import "./FileFieldControl.css";

import type { FileField } from "../types/api";
import { fieldMultiplicity } from "../domain/fields";

type PreviewFile = {
  name: string;
  url: string;
  type?: string;
};

type FileFieldControlProps = {
  field: FileField;
  value: unknown;
  files: File[];
  removed: string[];
  fileUrl: (filename: string, thumb?: string) => string;
  onValueChange: (value: string | string[]) => void;
  onFilesChange: (files: File[]) => void;
  onRemovedChange: (names: string[]) => void;
};

function fileNames(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String).filter(Boolean);
  return value ? [String(value)] : [];
}

function fileType(name: string) {
  // Keep the client preview list aligned with HttpApi's inline-safe extensions.
  // Do not trust File.type here: a browser can report image/svg+xml for an
  // untrusted SVG while the server deliberately serves it as a download.
  const lower = name.toLowerCase();
  if (/\.(gif|jpe?g|png|webp)$/.test(lower)) return "image";
  if (/\.(m4a|mp3|ogg|wav)$/.test(lower)) return "audio";
  if (/\.(m4v|mp4|webm)$/.test(lower)) return "video";
  if (/\.pdf$/.test(lower)) return "pdf";
  return "file";
}

function downloadUrl(url: string) {
  // Blob URLs are local previews. Appending a query string turns them into a
  // different, invalid URL; use the download attribute for those instead.
  if (url.startsWith("blob:")) return url;
  return `${url}${url.includes("?") ? "&" : "?"}download=1`;
}

function appendFiles(existing: File[], additions: File[], maxAdditional: number) {
  if (maxAdditional <= 0) return existing;
  const seen = new Set(existing.map((file) => `${file.name}:${file.size}:${file.lastModified}`));
  const next = [...existing];
  let added = 0;
  for (const file of additions) {
    const key = `${file.name}:${file.size}:${file.lastModified}`;
    if (seen.has(key)) continue;
    seen.add(key);
    next.push(file);
    added += 1;
    if (added >= maxAdditional) break;
  }
  return next;
}

export function FileFieldControl({
  field,
  value,
  files,
  removed,
  fileUrl,
  onValueChange,
  onFilesChange,
  onRemovedChange
}: FileFieldControlProps) {
  const { t } = useTranslation();
  const [dragging, setDragging] = useState(false);
  const [preview, setPreview] = useState<PreviewFile | null>(null);
  const [draggedExisting, setDraggedExisting] = useState<string | null>(null);
  const [draggedNew, setDraggedNew] = useState<number | null>(null);
  const [limitNotice, setLimitNotice] = useState(false);
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction(
    () => setPreview(null),
    { active: preview !== null }
  );

  const allExisting = useMemo(() => fileNames(value), [value]);
  const visibleExisting = useMemo(
    () => allExisting.filter((name) => !removed.includes(name)),
    [allExisting, removed]
  );
  const limit = fieldMultiplicity(field);
  const available = Math.max(0, limit - visibleExisting.length - files.length);
  const [localUrls, setLocalUrls] = useState<string[]>([]);

  useEffect(() => {
    const nextUrls = files.map((file) => URL.createObjectURL(file));
    setLocalUrls(nextUrls);
    return () => {
      for (const url of nextUrls) URL.revokeObjectURL(url);
    };
  }, [files]);

  function emitExisting(next: string[]) {
    onValueChange(limit === 1 ? (next[0] ?? "") : next);
  }

  function addFiles(nextFiles: File[]) {
    const next = appendFiles(files, nextFiles, available);
    if (nextFiles.length > 0) setLimitNotice(next.length < files.length + nextFiles.length);
    onFilesChange(next);
  }

  function removeExisting(name: string) {
    onRemovedChange(removed.includes(name) ? removed : [...removed, name]);
  }

  function restoreExisting(name: string) {
    onRemovedChange(removed.filter((item) => item !== name));
  }

  function moveExisting(source: string, target: string) {
    if (removed.length > 0) return;
    const from = visibleExisting.indexOf(source);
    const to = visibleExisting.indexOf(target);
    if (from === -1 || to === -1 || from === to) return;
    const reordered = [...visibleExisting];
    const [moved] = reordered.splice(from, 1);
    reordered.splice(to, 0, moved);
    // Keep files marked for deletion in their original positions; the server only
    // observes the remaining value plus the explicit `field-` deletion marker.
    const next = allExisting.map((name) => (removed.includes(name) ? name : reordered.shift() ?? name));
    emitExisting(next);
  }

  function moveNew(source: number, target: number) {
    if (source === target || source < 0 || target < 0) return;
    const next = [...files];
    const [moved] = next.splice(source, 1);
    if (!moved) return;
    next.splice(target, 0, moved);
    onFilesChange(next);
  }

  const accept = (field.mimeTypes ?? []).join(",");
  const thumb = field.thumbs?.[0];

  return (
    <section className="file-field-control" aria-label={field.name}>
      <header className="file-field-control-head">
        <div>
          <strong>{field.name}</strong>
          <span>
            {t("parity.files.max_files", { count: limit, defaultValue: "Up to {{count}} file(s)" })}
          </span>
        </div>
        <label className="subtle file-field-upload-button">
          <Upload size={15} />
          {t("parity.files.add_files", "Add files")}
          <input
            name={`${field.name}+`}
            type="file"
            multiple={limit > 1}
            accept={accept}
            onChange={(event) => {
              addFiles(Array.from(event.target.files ?? []));
              event.currentTarget.value = "";
            }}
          />
        </label>
      </header>

      <div
        className={`file-drop-zone${dragging ? " dragging" : ""}`}
        onDragEnter={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={(event) => {
          if (event.currentTarget === event.target) setDragging(false);
        }}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          addFiles(Array.from(event.dataTransfer.files));
        }}
      >
        <Upload size={17} />
        <span>{t("parity.files.drop_here", "Drop files here, or use Add files")}</span>
        {(available === 0 || limitNotice) && <em>{t("parity.files.limit_reached", "File limit reached")}</em>}
      </div>

      {(visibleExisting.length > 0 || files.length > 0 || removed.length > 0) && (
        <div className="file-field-list">
          {visibleExisting.map((name) => {
            const type = fileType(name);
            const url = fileUrl(name, type === "image" ? thumb : undefined);
            return (
              <article
                className="file-field-item"
                key={name}
                draggable={limit > 1 && removed.length === 0}
                onDragStart={() => {
                  if (removed.length === 0) setDraggedExisting(name);
                }}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                  event.preventDefault();
                  if (draggedExisting) moveExisting(draggedExisting, name);
                  setDraggedExisting(null);
                }}
                onDragEnd={() => setDraggedExisting(null)}
              >
                {limit > 1 && <GripVertical className="file-item-grip" size={15} aria-hidden="true" />}
                <FileThumbnail type={type} name={name} url={url} />
                <span className="file-item-name" title={name}>{name}</span>
                <div className="file-item-actions">
                  <button type="button" className="icon-button" onClick={() => setPreview({ name, url: fileUrl(name), type })} title={t("parity.files.preview", "Preview")} aria-label={t("parity.files.preview", "Preview")}>
                    <Eye size={15} />
                  </button>
                  <a className="icon-button" href={`${fileUrl(name)}${fileUrl(name).includes("?") ? "&" : "?"}download=1`} title={t("actions.download", "Download")} aria-label={t("actions.download", "Download")}>
                    <Download size={15} />
                  </a>
                  <button type="button" className="icon-button danger" onClick={() => removeExisting(name)} title={t("actions.remove", "Remove")} aria-label={t("actions.remove", "Remove")}>
                    <Trash2 size={15} />
                  </button>
                </div>
              </article>
            );
          })}

          {files.map((file, index) => {
            const url = localUrls[index];
            const type = fileType(file.name);
            return (
              <article
                className="file-field-item pending"
                key={`${file.name}:${file.size}:${file.lastModified}`}
                draggable={limit > 1}
                onDragStart={() => setDraggedNew(index)}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                  event.preventDefault();
                  if (draggedNew !== null) moveNew(draggedNew, index);
                  setDraggedNew(null);
                }}
                onDragEnd={() => setDraggedNew(null)}
              >
                {limit > 1 && <GripVertical className="file-item-grip" size={15} aria-hidden="true" />}
                <FileThumbnail type={type} name={file.name} url={url} />
                <span className="file-item-name" title={file.name}>{file.name}</span>
                <em>{t("parity.files.pending_upload", "Pending upload")}</em>
                <div className="file-item-actions">
                  <button type="button" className="icon-button" onClick={() => setPreview({ name: file.name, url, type })} title={t("parity.files.preview", "Preview")} aria-label={t("parity.files.preview", "Preview")}>
                    <Eye size={15} />
                  </button>
                  <button type="button" className="icon-button danger" onClick={() => {
                    setLimitNotice(false);
                    onFilesChange(files.filter((_, itemIndex) => itemIndex !== index));
                  }} title={t("actions.remove", "Remove")} aria-label={t("actions.remove", "Remove")}>
                    <X size={15} />
                  </button>
                </div>
              </article>
            );
          })}

          {removed.map((name) => (
            <article className="file-field-item removed" key={`removed-${name}`}>
              <FileText size={16} />
              <span className="file-item-name">{name}</span>
              <em>{t("parity.files.marked_for_removal", "Marked for removal")}</em>
              <button type="button" className="subtle compact" onClick={() => restoreExisting(name)}>
                <RotateCcw size={14} />
                {t("actions.restore", "Restore")}
              </button>
            </article>
          ))}
        </div>
      )}

      {preview && (
        <div
          className="file-preview-backdrop"
          role="presentation"
          onMouseDown={onBackdropMouseDown}
          onMouseUp={onBackdropMouseUp}
        >
          <section ref={dialogRef} className="file-preview-dialog" role="dialog" aria-modal="true" aria-label={preview.name} tabIndex={-1}>
            <header>
              <strong>{preview.name}</strong>
              <button type="button" className="icon-button" onClick={() => setPreview(null)} title={t("actions.close", "Close")} aria-label={t("actions.close", "Close")}>
                <X size={16} />
              </button>
            </header>
            <PreviewContent preview={preview} />
          </section>
        </div>
      )}
    </section>
  );
}

function FileThumbnail({ type, name, url }: { type: string; name: string; url: string }) {
  if (type === "image") return <img className="file-thumbnail" src={url} alt={name} />;
  if (type === "audio") return <Music2 className="file-thumbnail-icon" size={18} aria-label={name} />;
  if (type === "video") return <Video className="file-thumbnail-icon" size={18} aria-label={name} />;
  return type === "pdf" ? <FileText className="file-thumbnail-icon" size={18} aria-label={name} /> : <ImageIcon className="file-thumbnail-icon" size={18} aria-label={name} />;
}

function PreviewContent({ preview }: { preview: PreviewFile }) {
  if (preview.type === "image") return <img className="file-preview-image" src={preview.url} alt={preview.name} />;
  if (preview.type === "audio") return <audio className="file-preview-media" controls src={preview.url} />;
  if (preview.type === "video") return <video className="file-preview-media" controls src={preview.url} />;
  if (preview.type === "pdf") {
    return <iframe className="file-preview-pdf" title={preview.name} src={preview.url} sandbox="" referrerPolicy="no-referrer" />;
  }
  const localBlob = preview.url.startsWith("blob:");
  return (
    <a className="subtle" href={downloadUrl(preview.url)} download={localBlob ? preview.name : undefined}>
      <Download size={16} />
      {preview.name}
    </a>
  );
}
