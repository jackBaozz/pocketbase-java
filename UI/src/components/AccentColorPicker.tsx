import { useEffect, useMemo, useRef, useState } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";
import { ChevronDown, Pipette } from "lucide-react";
import { useTranslation } from "react-i18next";
import "./AccentColorPicker.css";

/** Official PocketBase-style accent presets (screenshot parity). */
export const ACCENT_PRESETS = ["#e11d48", "#0f766e", "#ea580c", "#1055c9", "#7c3aed"] as const;

type AccentColorPickerProps = {
  value: string;
  onChange: (hex: string) => void;
  error?: string;
};

type Hsv = { h: number; s: number; v: number };

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  if (!/^#[0-9a-f]{6}$/i.test(hex)) return null;
  return {
    r: Number.parseInt(hex.slice(1, 3), 16),
    g: Number.parseInt(hex.slice(3, 5), 16),
    b: Number.parseInt(hex.slice(5, 7), 16)
  };
}

function rgbToHex(r: number, g: number, b: number) {
  const to = (n: number) => clamp(Math.round(n), 0, 255).toString(16).padStart(2, "0");
  return `#${to(r)}${to(g)}${to(b)}`;
}

function rgbToHsv(r: number, g: number, b: number): Hsv {
  const rn = r / 255;
  const gn = g / 255;
  const bn = b / 255;
  const max = Math.max(rn, gn, bn);
  const min = Math.min(rn, gn, bn);
  const delta = max - min;
  let h = 0;
  if (delta !== 0) {
    if (max === rn) h = ((gn - bn) / delta) % 6;
    else if (max === gn) h = (bn - rn) / delta + 2;
    else h = (rn - gn) / delta + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  const s = max === 0 ? 0 : delta / max;
  return { h, s, v: max };
}

function hsvToRgb(h: number, s: number, v: number) {
  const c = v * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = v - c;
  let rp = 0;
  let gp = 0;
  let bp = 0;
  if (h < 60) [rp, gp, bp] = [c, x, 0];
  else if (h < 120) [rp, gp, bp] = [x, c, 0];
  else if (h < 180) [rp, gp, bp] = [0, c, x];
  else if (h < 240) [rp, gp, bp] = [0, x, c];
  else if (h < 300) [rp, gp, bp] = [x, 0, c];
  else [rp, gp, bp] = [c, 0, x];
  return {
    r: Math.round((rp + m) * 255),
    g: Math.round((gp + m) * 255),
    b: Math.round((bp + m) * 255)
  };
}

function hexToHsv(hex: string): Hsv {
  const rgb = hexToRgb(hex) ?? { r: 16, g: 85, b: 201 };
  return rgbToHsv(rgb.r, rgb.g, rgb.b);
}

function hsvToHex(hsv: Hsv) {
  const rgb = hsvToRgb(hsv.h, hsv.s, hsv.v);
  return rgbToHex(rgb.r, rgb.g, rgb.b);
}

export function AccentColorPicker({ value, onChange, error }: AccentColorPickerProps) {
  const { t } = useTranslation();
  const rootRef = useRef<HTMLDivElement>(null);
  const svRef = useRef<HTMLDivElement>(null);
  const hueRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const [hsv, setHsv] = useState<Hsv>(() => hexToHsv(value || "#1055c9"));
  const dragging = useRef<"sv" | "hue" | null>(null);

  const hex = useMemo(() => hsvToHex(hsv), [hsv]);
  const rgb = useMemo(() => hsvToRgb(hsv.h, hsv.s, hsv.v), [hsv]);
  const pureHue = useMemo(() => hsvToHex({ h: hsv.h, s: 1, v: 1 }), [hsv.h]);

  useEffect(() => {
    if (!open) setHsv(hexToHsv(value || "#1055c9"));
  }, [value, open]);

  useEffect(() => {
    if (!open) return;
    function onDoc(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
        setAdvanced(false);
      }
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
        setAdvanced(false);
      }
    }
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  function commit(next: Hsv) {
    setHsv(next);
    onChange(hsvToHex(next));
  }

  function pickPreset(preset: string) {
    const next = hexToHsv(preset);
    commit(next);
    setOpen(false);
    setAdvanced(false);
  }

  function updateSvFromEvent(event: { clientX: number; clientY: number }) {
    const el = svRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const s = clamp((event.clientX - rect.left) / rect.width, 0, 1);
    const v = clamp(1 - (event.clientY - rect.top) / rect.height, 0, 1);
    commit({ ...hsv, s, v });
  }

  function updateHueFromEvent(event: { clientX: number }) {
    const el = hueRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const h = clamp(((event.clientX - rect.left) / rect.width) * 360, 0, 359.999);
    commit({ ...hsv, h });
  }

  function onSvPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    event.preventDefault();
    dragging.current = "sv";
    event.currentTarget.setPointerCapture(event.pointerId);
    updateSvFromEvent(event);
  }

  function onHuePointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    event.preventDefault();
    dragging.current = "hue";
    event.currentTarget.setPointerCapture(event.pointerId);
    updateHueFromEvent(event);
  }

  function onPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    if (dragging.current === "sv") updateSvFromEvent(event);
    if (dragging.current === "hue") updateHueFromEvent(event);
  }

  function onPointerUp(event: ReactPointerEvent<HTMLDivElement>) {
    if (dragging.current) {
      dragging.current = null;
      try {
        event.currentTarget.releasePointerCapture(event.pointerId);
      } catch {
        // ignore
      }
    }
  }

  function setChannel(channel: "r" | "g" | "b", raw: string) {
    const n = Number(raw);
    if (!Number.isFinite(n)) return;
    const nextRgb = { ...rgb, [channel]: clamp(n, 0, 255) };
    commit(rgbToHsv(nextRgb.r, nextRgb.g, nextRgb.b));
  }

  async function eyedropper() {
    const EyeDropperCtor = (window as unknown as { EyeDropper?: new () => { open: () => Promise<{ sRGBHex: string }> } })
      .EyeDropper;
    if (!EyeDropperCtor) return;
    try {
      const result = await new EyeDropperCtor().open();
      const picked = result.sRGBHex?.toLowerCase();
      if (picked && /^#[0-9a-f]{6}$/.test(picked)) {
        commit(hexToHsv(picked));
      }
    } catch {
      // user cancelled
    }
  }

  return (
    <div className="accent-picker" ref={rootRef}>
      <span className="accent-picker-label">{t("settings.accent_color", "Accent color")}</span>
      <button
        type="button"
        className="accent-picker-trigger"
        style={{ background: hex }}
        onClick={() => {
          setOpen((value) => !value);
          if (open) setAdvanced(false);
        }}
        aria-expanded={open}
        aria-haspopup="dialog"
        title={t("settings.accent_color", "Accent color")}
      >
        <span className="accent-picker-hex">{hex}</span>
        <ChevronDown size={14} className={`accent-picker-chevron${open ? " is-open" : ""}`} />
      </button>

      {error && <span className="form-error accent-picker-error">{error}</span>}

      {open && (
        <div className="accent-picker-popover" role="dialog" aria-label={t("settings.accent_color", "Accent color")}>
          {!advanced ? (
            <div className="accent-picker-presets">
              {ACCENT_PRESETS.map((preset) => (
                <button
                  key={preset}
                  type="button"
                  className={`accent-picker-swatch${hex === preset ? " is-selected" : ""}`}
                  style={{ background: preset }}
                  onClick={() => pickPreset(preset)}
                  title={preset}
                  aria-label={preset}
                />
              ))}
              <button
                type="button"
                className="accent-picker-more"
                onClick={() => setAdvanced(true)}
                title={t("settings.accent_custom", "Custom color")}
                aria-label={t("settings.accent_custom", "Custom color")}
              >
                <Pipette size={14} />
              </button>
            </div>
          ) : (
            <div className="accent-picker-advanced">
              <div
                ref={svRef}
                className="accent-picker-sv"
                style={{ backgroundColor: pureHue }}
                onPointerDown={onSvPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={onPointerUp}
                onPointerCancel={onPointerUp}
              >
                <div className="accent-picker-sv-white" />
                <div className="accent-picker-sv-black" />
                <span
                  className="accent-picker-sv-thumb"
                  style={{ left: `${hsv.s * 100}%`, top: `${(1 - hsv.v) * 100}%`, background: hex }}
                />
              </div>

              <div className="accent-picker-tools">
                <button
                  type="button"
                  className="accent-picker-eye"
                  onClick={() => void eyedropper()}
                  title={t("settings.accent_eyedropper", "Pick color from screen")}
                  aria-label={t("settings.accent_eyedropper", "Pick color from screen")}
                >
                  <Pipette size={15} />
                </button>
                <span className="accent-picker-solid" style={{ background: hex }} />
                <div
                  ref={hueRef}
                  className="accent-picker-hue"
                  onPointerDown={onHuePointerDown}
                  onPointerMove={onPointerMove}
                  onPointerUp={onPointerUp}
                  onPointerCancel={onPointerUp}
                >
                  <span className="accent-picker-hue-thumb" style={{ left: `${(hsv.h / 360) * 100}%` }} />
                </div>
              </div>

              <div className="accent-picker-rgb">
                {(["r", "g", "b"] as const).map((channel) => (
                  <label key={channel} className="accent-picker-rgb-field">
                    <input
                      type="number"
                      min={0}
                      max={255}
                      value={rgb[channel]}
                      onChange={(event) => setChannel(channel, event.target.value)}
                    />
                    <span>{channel.toUpperCase()}</span>
                  </label>
                ))}
              </div>

              <button type="button" className="accent-picker-back" onClick={() => setAdvanced(false)}>
                {t("settings.accent_presets", "Presets")}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default AccentColorPicker;
