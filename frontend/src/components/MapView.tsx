import 'leaflet/dist/leaflet.css';
import L from 'leaflet';
import { MapContainer, Marker, Polyline, Popup, TileLayer } from 'react-leaflet';
import type { ReactNode } from 'react';

export const KANDY: [number, number] = [7.2936, 80.6413];

// Emoji markers avoid Leaflet's default icon image paths, which break under bundlers.
const icon = (emoji: string, ring: string) =>
  L.divIcon({
    className: '',
    html: `<div style="font-size:22px;line-height:30px;width:30px;height:30px;text-align:center;border-radius:50%;background:#fff;border:3px solid ${ring};box-shadow:0 1px 4px rgba(0,0,0,.3)">${emoji}</div>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15],
  });

const ICONS = {
  RENTED: icon('🛵', '#3b6fd1'),
  AVAILABLE: icon('🛵', '#1f7a4d'),
  MAINTENANCE: icon('🔧', '#e08a1e'),
};

export interface MapMarker {
  id: string;
  lat: number;
  lng: number;
  kind: keyof typeof ICONS;
  popup: ReactNode;
}

export default function MapView({
  markers,
  trail,
  center = KANDY,
  zoom = 13,
}: {
  markers: MapMarker[];
  trail?: [number, number][];
  center?: [number, number];
  zoom?: number;
}) {
  return (
    <div className="map">
      <MapContainer center={center} zoom={zoom} style={{ height: '100%', width: '100%' }} scrollWheelZoom>
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        {trail && trail.length > 1 && <Polyline positions={trail} pathOptions={{ color: '#3b6fd1', weight: 4 }} />}
        {markers.map((m) => (
          <Marker key={m.id} position={[m.lat, m.lng]} icon={ICONS[m.kind]}>
            <Popup>{m.popup}</Popup>
          </Marker>
        ))}
      </MapContainer>
    </div>
  );
}
