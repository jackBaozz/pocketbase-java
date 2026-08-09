import { lazy, Suspense, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import "./GeoPointControl.css";

const GeoPointMap = lazy(() => import("./GeoPointMap"));

type GeoPointControlProps = {
  name: string;
  meta: ReactNode;
  value: unknown;
  onChange: (value: { lat: number; lon: number } | null) => void;
};

type CoordinateDraft = {
  lat: string;
  lon: string;
};

function numberValue(value: unknown) {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return value;
}

function initialDraft(value: unknown): CoordinateDraft {
  if (!value || typeof value !== "object") return { lat: "", lon: "" };
  const source = value as Record<string, unknown>;
  const lat = numberValue(source.lat);
  const lon = numberValue(source.lon);
  return {
    lat: lat === null ? "" : String(lat),
    lon: lon === null ? "" : String(lon)
  };
}

function coordinatePoint(draft: CoordinateDraft) {
  const latText = draft.lat.trim();
  const lonText = draft.lon.trim();
  if (!latText && !lonText) return null;
  if (!latText || !lonText) return undefined;
  const lat = Number(latText);
  const lon = Number(lonText);
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    return undefined;
  }
  return { lat, lon };
}

function roundedCoordinate(value: number) {
  return String(Math.round(value * 1_000_000) / 1_000_000);
}

function pointSignature(value: { lat: number; lon: number } | null) {
  return value ? `${value.lat},${value.lon}` : "null";
}

export function GeoPointControl({ name, meta, value, onChange }: GeoPointControlProps) {
  const { t } = useTranslation();
  const derivedDraft = useMemo(() => initialDraft(value), [value]);
  const [draft, setDraft] = useState<CoordinateDraft>(derivedDraft);
  const [mapOpen, setMapOpen] = useState(false);
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState("");
  const lastEmittedSignature = useRef<string | null>(null);

  useEffect(() => {
    const nextPoint = coordinatePoint(derivedDraft);
    const signature = pointSignature(nextPoint && nextPoint !== undefined ? nextPoint : null);
    if (lastEmittedSignature.current === signature) return;
    setDraft((current) =>
      current.lat === derivedDraft.lat && current.lon === derivedDraft.lon ? current : derivedDraft
    );
  }, [derivedDraft.lat, derivedDraft.lon]);

  const point = coordinatePoint(draft);
  const coordinateError =
    point === undefined
      ? t("parity.geo.invalid_coordinates", "Enter a latitude between -90 and 90 and a longitude between -180 and 180.")
      : "";
  const center: [number, number] = point && point !== undefined ? [point.lat, point.lon] : [0, 0];
  const openStreetMapUrl = `https://www.openstreetmap.org/#map=${point && point !== undefined ? 13 : 2}/${center[0]}/${center[1]}`;

  function applyDraft(next: CoordinateDraft) {
    setDraft(next);
    const nextPoint = coordinatePoint(next);
    const emitted = nextPoint && nextPoint !== undefined ? nextPoint : null;
    lastEmittedSignature.current = pointSignature(emitted);
    onChange(emitted);
  }

  function applyPoint(next: { lat: number; lon: number }) {
    applyDraft({ lat: roundedCoordinate(next.lat), lon: roundedCoordinate(next.lon) });
    setLocationError("");
  }

  function useCurrentLocation() {
    if (!navigator.geolocation) {
      setLocationError(t("parity.geo.location_unsupported", "This browser does not provide your location."));
      return;
    }
    setLocating(true);
    setLocationError("");
    navigator.geolocation.getCurrentPosition(
      (position) => {
        applyPoint({ lat: position.coords.latitude, lon: position.coords.longitude });
        setLocating(false);
      },
      () => {
        setLocationError(t("parity.geo.location_failed", "Unable to read your location. Check the browser permission and try again."));
        setLocating(false);
      },
      { enableHighAccuracy: true, maximumAge: 60_000, timeout: 10_000 }
    );
  }

  return (
    <section className="record-field-card wide geo-point-control">
      <span>
        <strong>{name}</strong>
        {meta}
      </span>
      <div className="geo-point-coordinates">
        <label>
          {t("parity.geo.latitude", "Latitude")}
          <input
            name={`${name}.lat`}
            type="number"
            inputMode="decimal"
            min={-90}
            max={90}
            step="any"
            value={draft.lat}
            onChange={(event) => applyDraft({ ...draft, lat: event.target.value })}
          />
        </label>
        <label>
          {t("parity.geo.longitude", "Longitude")}
          <input
            name={`${name}.lon`}
            type="number"
            inputMode="decimal"
            min={-180}
            max={180}
            step="any"
            value={draft.lon}
            onChange={(event) => applyDraft({ ...draft, lon: event.target.value })}
          />
        </label>
      </div>
      {coordinateError && <p className="geo-point-error">{coordinateError}</p>}
      <div className="geo-point-actions">
        <button type="button" className="subtle compact" onClick={() => setMapOpen((open) => !open)}>
          {mapOpen ? t("parity.geo.hide_map", "Hide map") : t("parity.geo.pick_on_map", "Pick on map")}
        </button>
        <button type="button" className="subtle compact" onClick={useCurrentLocation} disabled={locating}>
          {locating ? t("parity.geo.locating", "Locating...") : t("parity.geo.use_current_location", "Use current location")}
        </button>
        <a className="subtle compact" href={openStreetMapUrl} target="_blank" rel="noreferrer">
          {t("parity.geo.open_map", "Open map")}
        </a>
      </div>
      <p className="geo-point-help">{t("parity.geo.map_help", "Click the map to choose the exact latitude and longitude.")}</p>
      {locationError && <p className="geo-point-location-error">{locationError}</p>}
      {mapOpen && (
        <div className="geo-point-map-shell">
          <Suspense fallback={<div className="geo-point-map-loading">{t("common.loading", "Loading...")}</div>}>
            <GeoPointMap center={center} hasPosition={Boolean(point)} onPick={applyPoint} />
          </Suspense>
        </div>
      )}
    </section>
  );
}
