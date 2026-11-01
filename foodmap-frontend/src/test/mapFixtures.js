// Server-shaped bodies (already unwrapped) for /map tests.

export const mapCatalogBody = () => ({
  version: 'v1',
  foods: [
    { key: 'ca-phe', label: 'Cà phê', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', discoverable: true, artKey: 'ca-phe' },
    { key: 'bun', label: 'Bún', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', discoverable: true, artKey: 'bun' },
    { key: 'pho', label: 'Phở', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', discoverable: true, artKey: 'pho' },
    { key: 'com', label: 'Cơm', kind: 'MEAL', parentCategory: 'rice', tone: 'turmeric', discoverable: true, artKey: 'com' },
    { key: 'banh-mi', label: 'Bánh mì', kind: 'MEAL', parentCategory: 'bakery', tone: 'crust', discoverable: true, artKey: 'banh-mi' },
    { key: 'kem', label: 'Kem', kind: 'DESSERT', parentCategory: 'dessert', tone: 'dragonfruit', discoverable: true, artKey: 'kem' },
    { key: 'tra', label: 'Trà', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', discoverable: false, artKey: 'tra' },
  ],
  areas: [],
});

const place = (id, name, foodKeys, i) => ({
  id, name, address: `${id} Đường Số ${id}, Quận 1`,
  latitude: 10.776 + i * 0.001, longitude: 106.700 + i * 0.001,
  avgRating: 0, reviewCount: 0, ...(foodKeys ? { foodKeys } : {}),
});

export const MAP_RESTAURANTS = [
  place(1, 'Phở Hòa', ['pho'], 0),
  place(2, 'Bún Phở Cô Ba', ['bun', 'pho'], 1),
  place(3, 'Bánh Mì Hai Lúa', ['banh-mi'], 2),
  place(4, 'Cafe Góc Phố', ['ca-phe', 'tra'], 3),
  // a name that says phở, but the server didn't classify it: never a match
  place(5, 'Phở Không Phân Loại', null, 4),
  place(6, 'Quán Đủ Món', ['bun', 'pho', 'banh-mi', 'ca-phe'], 5),
];
