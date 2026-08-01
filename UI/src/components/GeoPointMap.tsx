import { useEffect } from "react";
import { CircleMarker, MapContainer, TileLayer, useMap, useMapEvents } from "react-leaflet";
import "leaflet/dist/leaflet.css";

type GeoPointMapProps = {
  center: [number, number];
  hasPosition: boolean;
  onPick: (point: { lat: number; lon: number }) => void;
};

function Recenter({ center }: Pick<GeoPointMapProps, "center">) {
  const map = useMap();

  useEffect(() => {
    map.setView(center, map.getZoom(), { animate: false });
  }, [center[0], center[1], map]);

  return null;
}

function MapPicker({ onPick }: Pick<GeoPointMapProps, "onPick">) {
  useMapEvents({
    click(event) {
      onPick({ lat: event.latlng.lat, lon: event.latlng.lng });
    }
  });

  return null;
}

/**
 * Loaded only after a user opens the picker. Leaflet and its tile layer are
 * intentionally kept out of the normal record-editor bundle.
 */
export default function GeoPointMap({ center, hasPosition, onPick }: GeoPointMapProps) {
  return (
    <MapContainer center={center} zoom={hasPosition ? 13 : 2} scrollWheelZoom className="geo-point-map-canvas">
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <Recenter center={center} />
      <MapPicker onPick={onPick} />
      {hasPosition && <CircleMarker center={center} radius={8} pathOptions={{ color: "#1055c9", fillColor: "#1055c9", fillOpacity: 0.72 }} />}
    </MapContainer>
  );
}
