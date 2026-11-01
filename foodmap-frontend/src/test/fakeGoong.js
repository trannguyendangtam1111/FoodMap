// A stand-in for Goong GL in jsdom: markers and popups are plain DOM inside
// the map container, so pins can be inspected; camera calls are recorded.
// Use with: vi.mock('@goongmaps/goong-js', () => import('../test/fakeGoong.js'))

const state = { maps: [] };

class FakeMap {
  constructor(options) {
    this.options = options;
    this.handlers = {};
    this.camera = [];
    this.center = { lng: options.center[0], lat: options.center[1] };
    this.zoom = options.zoom;
    this.layer = document.createElement('div');
    this.layer.dataset.mapLayer = '';
    options.container.appendChild(this.layer);
    state.maps.push(this);
  }
  addControl() { return this; }
  on(event, fn) { (this.handlers[event] ??= []).push(fn); return this; }
  fire(event) { (this.handlers[event] ?? []).forEach((fn) => fn()); }
  getBounds() {
    const { lng, lat } = this.center;
    return {
      getSouth: () => lat - 0.01, getWest: () => lng - 0.02, getNorth: () => lat + 0.01, getEast: () => lng + 0.02,
    };
  }
  getCenter() { return this.center; }
  getZoom() { return this.zoom; }
  flyTo(o) { this.camera.push(['flyTo', o]); }
  easeTo(o) { this.camera.push(['easeTo', o]); }
  fitBounds(b, o) { this.camera.push(['fitBounds', o]); }
  zoomOut(o) { this.camera.push(['zoomOut', o]); }
  resize() { this.camera.push(['resize']); }
  remove() { this.layer.remove(); }
}

class FakeMarker {
  constructor({ element }) { this.el = element; }
  setLngLat(ll) { this.ll = ll; return this; }
  getLngLat() { return { lng: this.ll[0], lat: this.ll[1] }; }
  addTo(map) { map.layer.appendChild(this.el); return this; }
  remove() { this.el.remove(); }
}

class FakePopup {
  constructor(options) { this.options = options; this.handlers = {}; }
  setDOMContent(node) { this.node = node; node.dataset.popup = ''; return this; }
  setLngLat() { return this; }
  addTo(map) { map.layer.appendChild(this.node); return this; }
  on(event, fn) { this.handlers[event] = fn; return this; }
  remove() { this.node.remove(); this.handlers.close?.(); }
}

class LngLatBounds { extend() { return this; } }
class Control {}

export default {
  Map: FakeMap, Marker: FakeMarker, Popup: FakePopup, LngLatBounds,
  NavigationControl: Control, ScaleControl: Control, GeolocateControl: Control,
  __state: state,
};
