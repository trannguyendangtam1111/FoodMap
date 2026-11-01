import { useEffect, useId, useRef } from 'react';
import Button from '../ui/Button';
import Skeleton from '../ui/Skeleton';
import useCatalog from '../../hooks/useCatalog';

/**
 * AreaPicker — the FoodMap discovery areas, as a labelled list inside an
 * inline disclosure.
 *
 * The list is the server's (GET /api/v1/catalog → areas: key, label, order),
 * in the server's order; nothing about areas is known locally, and only the
 * areas the server currently offers appear. Each area is a toggle button
 * (aria-pressed marks the current one), so moving through the list never
 * changes the scope by itself. Escape closes and hands focus back.
 */
export default function AreaPicker({ id, selectedKey, onSelect, onClose }) {
  const { status, catalog, retry } = useCatalog({ enabled: true });
  const headingId = useId();
  const listRef = useRef(null);

  // Land keyboard users on the list once it is there (the current area first).
  useEffect(() => {
    if (status !== 'ready') return;
    const list = listRef.current;
    (list?.querySelector('[aria-pressed="true"]') ?? list?.querySelector('button'))?.focus({ preventScroll: true });
  }, [status]);

  const onKeyDown = (event) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
    }
  };

  return (
    <div id={id} onKeyDown={onKeyDown} className="mt-3 rounded-card border border-line bg-surface p-3 sm:p-4">
      <p id={headingId} className="mb-2 text-small font-semibold text-ink">Chọn khu vực</p>
      {status === 'error' ? (
        <div className="flex flex-wrap items-center gap-3">
          <p className="text-small text-ink-muted">Chưa tải được danh sách khu vực.</p>
          <Button variant="secondary" size="sm" onClick={retry}>Thử lại</Button>
        </div>
      ) : status !== 'ready' ? (
        <div className="flex flex-wrap gap-2" aria-hidden="true">
          {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-11 w-24 rounded-full" />)}
        </div>
      ) : catalog.areas.length === 0 ? (
        <p className="text-small text-ink-muted">Hiện chưa có khu vực nào để chọn.</p>
      ) : (
        <ul ref={listRef} aria-labelledby={headingId} className="flex flex-wrap gap-2">
          {catalog.areas.map((area) => {
            const selected = area.key === selectedKey;
            return (
              <li key={area.key}>
                <button
                  type="button"
                  aria-pressed={selected}
                  onClick={() => onSelect(area)}
                  className={`inline-flex min-h-11 items-center rounded-full border-2 px-4 text-small font-semibold
                              text-ink transition-colors duration-fast
                              ${selected ? 'border-ink bg-brand-100' : 'border-line-strong/70 bg-surface hover:bg-surface-sunken'}`}
                >
                  {area.label}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        <p className="text-label font-medium text-ink-subtle">
          Khu vực là vùng tìm kiếm của FoodMap, không phải ranh giới hành chính.
        </p>
        <Button variant="ghost" size="sm" onClick={onClose}>Đóng</Button>
      </div>
    </div>
  );
}
