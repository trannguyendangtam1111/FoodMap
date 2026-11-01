// Stroke icons on a 24px grid, rounded caps/joins. Add paths here instead of
// inlining SVG in components.
const PATHS = {
  'alert':        ['M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z'],
  'chevron-left': ['M15 19l-7-7 7-7'],
  'clock':        ['M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z'],
  // Leaves FoodMap (opens another site in a new tab)
  'external':     ['M13.5 6H5.25A2.25 2.25 0 0 0 3 8.25v10.5A2.25 2.25 0 0 0 5.25 21h10.5A2.25 2.25 0 0 0 18 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25'],
  'globe':        ['M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9'],
  'home':         ['m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25'],
  'logout':       ['M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15m3 0 3-3m0 0-3-3m3 3H9'],
  'map':          ['M9 6.75 3.75 4.5v12.75L9 19.5m0-12.75 6-2.25m-6 2.25v12.75m6-15 5.25 2.25v12.75L15 17.25m0-12.75v12.75m0 0L9 19.5'],
  'map-pin':      ['M15 10.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0z', 'M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1 1 15 0z'],
  // "Chỉ đường" (directions)
  'navigation':   ['M3 11l19-9-9 19-2-8-8-2z'],
  'phone':        ['M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498A1 1 0 0121 15.72V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z'],
  'search':       ['M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z'],
  // Cà mèn (stacked tiffin) — the future "Ăn gì hôm nay?" signature object
  'tiffin':       ['M8 9V4h8v5', 'M5 9h14v3.5H5z', 'M5 12.5h14V16H5z', 'M5 16h14v2a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z'],
  'trash':        ['M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16'],
  'user':         ['M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0zM4.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 12 21.75c-2.676 0-5.216-.584-7.499-1.632z'],
  'utensils':     ['M7 3v8M4.5 3v5a2.5 2.5 0 0 0 5 0V3M7 11v10', 'M17 21V3c-2 1.5-3 4-3 7v4h3'],
  'x':            ['M6 18L18 6M6 6l12 12'],
};

/**
 * Decorative by default (aria-hidden). Pass `label` when the icon carries
 * meaning on its own, e.g. an icon-only button without visible text.
 */
export default function Icon({ name, size = 20, strokeWidth = 1.75, label, className = '' }) {
  const paths = PATHS[name];
  if (!paths) return null;

  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={`flex-shrink-0 ${className}`}
      {...(label ? { role: 'img', 'aria-label': label } : { 'aria-hidden': true })}
    >
      {paths.map((d) => <path key={d} d={d} />)}
    </svg>
  );
}
