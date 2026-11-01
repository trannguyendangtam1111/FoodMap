import { useEffect, useId, useRef, useState } from 'react';
import Button from '../ui/Button';
import Icon from '../ui/Icon';
import Skeleton from '../ui/Skeleton';
import FoodChip from './FoodChip';
import ScopeBar from '../location/ScopeBar';
import AreaPicker from '../location/AreaPicker';
import useLocationScope from '../../hooks/useLocationScope';
import useAvailableFoods from '../../hooks/useAvailableFoods';
import { MODES, inSentence, scopeLabel, scopeSearch } from '../../utils/scope';

/**
 * "Thèm gì?" — the foods FoodMap knows a restaurant for, in the current scope.
 *
 * Everything shown comes from GET /decisions/foods/available: which foods,
 * their labels, kinds, counts, the radius each count is for and the scope
 * phrase — in the server's (catalog) order. Nothing is classified, counted,
 * sorted or invented here. A count is supply FoodMap knows, never popularity.
 *
 * Scope: the city centre by default; "Gần bạn" when this tab already has the
 * user's location; "Gần tôi" asks for it only when pressed; "Khu vực" lists
 * the server's discovery areas. Changing scope always reloads the counts.
 */

const SKELETON_COUNT = 8;
const optionOf = (mode) => (mode === MODES.USER ? 'near' : mode === MODES.AREA ? 'area' : 'centre');

export default function ThemGiSection() {
  const { scope, selectCityCentre, selectArea, chooseNearMe, fallBackToCityCentre } = useLocationScope();
  const foods = useAvailableFoods(scope);
  const [locating, setLocating] = useState(false);
  const [picking, setPicking] = useState(false);
  const [notice, setNotice] = useState(null);
  const pickerId = useId();
  const scopeBarRef = useRef(null);
  const changeAreaRef = useRef(null);
  const focusAfterPick = useRef(false);
  const locateAbort = useRef(null);

  useEffect(() => () => locateAbort.current?.abort(), []);

  // The server refused this scope (an area no longer offered, a location it
  // can't use): say so and show the city centre instead — never invented counts.
  useEffect(() => {
    if (foods.status !== 'error' || foods.error?.status !== 400 || scope.mode === MODES.CITY_CENTER) return;
    setNotice(scope.mode === MODES.AREA
      ? 'Khu vực này hiện chưa dùng được, FoodMap đang hiển thị quanh trung tâm TP.HCM.'
      : 'Không dùng được vị trí này, FoodMap đang hiển thị quanh trung tâm TP.HCM.');
    fallBackToCityCentre();
  }, [foods.status, foods.error, scope.mode, fallBackToCityCentre]);

  // After choosing an area, hand focus to "Đổi khu vực" (the list closed under it).
  useEffect(() => {
    if (focusAfterPick.current && scope.mode === MODES.AREA && !picking) {
      focusAfterPick.current = false;
      changeAreaRef.current?.focus({ preventScroll: true });
    }
  }, [scope, picking]);

  const onScopeChange = async (option) => {
    setNotice(null);
    if (option === 'area') {
      setPicking(true);
      return;
    }
    setPicking(false);
    if (option === 'centre') {
      selectCityCentre();
      return;
    }
    // "Gần tôi" — the explicit action that may show the browser prompt.
    locateAbort.current?.abort();
    const controller = new AbortController();
    locateAbort.current = controller;
    setLocating(true);
    const ok = await chooseNearMe({ signal: controller.signal });
    if (controller.signal.aborted) return;
    setLocating(false);
    if (!ok) setNotice('Không lấy được vị trí. Bạn có thể chọn khu vực.');
  };

  const closePicker = () => {
    setPicking(false);
    // The radio for the scope still in use (not "Khu vực", which only showed the list).
    scopeBarRef.current?.querySelector(`input[value="${optionOf(scope.mode)}"]`)?.focus({ preventScroll: true });
  };

  const onPickArea = (area) => {
    focusAfterPick.current = true;
    setPicking(false);
    selectArea(area.key, area.label);
  };

  const data = foods.status === 'ready' ? foods.data : null;
  const label = data?.scope.label ?? scopeLabel(scope);
  const phrase = inSentence(label);
  const search = scopeSearch(scope);
  const selected = locating ? 'near' : picking ? 'area' : optionOf(scope.mode);

  const status = locating
    ? 'Đang lấy vị trí của bạn…'
    : foods.status === 'loading'
      ? `Đang tải các món ${phrase}…`
      : foods.status === 'error'
        ? 'Chưa tải được danh sách món.'
        : data.foods.length === 0
          ? `Chưa có món nào FoodMap biết ${phrase}.`
          : `${data.foods.length} món có quán ${phrase}.`;

  return (
    <section aria-labelledby="themgi-title" className="mx-auto max-w-6xl px-4 py-8 lg:py-10">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h2 id="themgi-title" className="font-display text-h2 text-ink">Thèm gì?</h2>
          <p className="mt-1 flex items-center gap-1.5 text-small font-semibold text-brand-800">
            <Icon name="map-pin" size={16} strokeWidth={2} />
            <span>{label}</span>
            {scope.mode === MODES.AREA && !picking && (
              <button
                ref={changeAreaRef}
                type="button"
                aria-expanded={false}
                aria-controls={pickerId}
                onClick={() => setPicking(true)}
                className="ml-1 inline-flex min-h-11 items-center rounded-full px-2 text-small font-semibold text-brand-700
                           underline decoration-brand-300 underline-offset-4 hover:text-brand-800"
              >
                Đổi khu vực
              </button>
            )}
          </p>
        </div>
        <div ref={scopeBarRef}>
          <ScopeBar value={selected} onChange={onScopeChange} busy={locating} />
        </div>
      </div>

      {picking && (
        <AreaPicker
          id={pickerId}
          selectedKey={scope.mode === MODES.AREA ? scope.areaKey : null}
          onSelect={onPickArea}
          onClose={closePicker}
        />
      )}

      {notice && (
        <p className="mt-3 rounded-tag bg-warning-tint px-3 py-2 text-small font-medium text-warning-ink">{notice}</p>
      )}

      <div aria-busy={foods.status === 'loading' || undefined} className="mt-4 min-h-[9.5rem] lg:min-h-[11rem]">
        {foods.status === 'loading' && <ChipSkeletons />}
        {/* a 400 for a user/area scope falls back to the city centre (effect above) */}
        {foods.status === 'error' && (foods.error?.status !== 400 || scope.mode === MODES.CITY_CENTER) && (
          <LoadError error={foods.error} onRetry={foods.retry} />
        )}
        {data && data.foods.length === 0 && (
          <EmptyState
            phrase={phrase}
            showCentre={scope.mode !== MODES.CITY_CENTER}
            onCentre={() => { setNotice(null); setPicking(false); selectCityCentre(); }}
            onArea={() => { setNotice(null); setPicking(true); }}
          />
        )}
        {data && data.foods.length > 0 && (
          <>
            <ul
              aria-label={`Các món có quán ${phrase}`}
              className="-mx-4 flex snap-x scroll-px-4 gap-2 overflow-x-auto px-4 pb-2 pt-1
                         sm:mx-0 sm:grid sm:grid-cols-4 sm:gap-3 sm:overflow-visible sm:px-0
                         md:grid-cols-6 lg:grid-cols-8"
            >
              {data.foods.map((food) => (
                <FoodChip
                  key={food.key}
                  food={food}
                  href={`/mon/${encodeURIComponent(food.key)}${search}`}
                  scopePhrase={phrase}
                />
              ))}
            </ul>
            <p className="mt-3 text-small text-ink-muted">
              Số quán FoodMap biết có món này (theo tên quán và thực đơn).
            </p>
            {data.unavailableCount > 0 && (
              <p className="mt-1 text-small text-ink-subtle">
                {data.unavailableCount} món khác chưa có quán {phrase}.
              </p>
            )}
          </>
        )}
      </div>

      <p role="status" aria-live="polite" aria-atomic="true" className="sr-only">{status}</p>
    </section>
  );
}

function ChipSkeletons() {
  return (
    <ul aria-hidden="true" className="-mx-4 flex gap-2 overflow-hidden px-4 pb-2 pt-1 sm:mx-0 sm:grid sm:grid-cols-4 sm:gap-3 sm:px-0 md:grid-cols-6 lg:grid-cols-8">
      {Array.from({ length: SKELETON_COUNT }, (_, i) => (
        <li key={i} className="flex w-[6.25rem] flex-shrink-0 flex-col items-center gap-2 py-1.5 sm:w-auto">
          <Skeleton className="h-20 w-20 rounded-full lg:h-24 lg:w-24" />
          <Skeleton className="h-3.5 w-14" />
          <Skeleton className="h-3 w-10" />
        </li>
      ))}
    </ul>
  );
}

function LoadError({ error, onRetry }) {
  const tooFast = error?.status === 429;
  const seconds = tooFast && Number.isFinite(error?.retryAfterSeconds) ? error.retryAfterSeconds : 0;
  const [wait, setWait] = useState(seconds);

  // Count down to the server's Retry-After; never retries by itself.
  useEffect(() => {
    if (seconds <= 0) return undefined;
    const until = Date.now() + seconds * 1000;
    const timer = setInterval(() => {
      const left = Math.max(0, Math.ceil((until - Date.now()) / 1000));
      setWait(left);
      if (left === 0) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [seconds]);

  const detail = !tooFast
    ? 'FoodMap đang gặp chút trục trặc, bạn thử lại nhé.'
    : seconds <= 0
      ? 'Bạn mở hơi nhanh, đợi một chút rồi thử lại nhé.'   // Retry-After not readable
      : wait > 0
        ? `Bạn mở hơi nhanh, đợi ${wait} giây rồi thử lại nhé.`
        : 'Giờ bạn thử lại được rồi.';

  return (
    <div className="flex flex-col items-start gap-3 rounded-card border border-line bg-surface p-4 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <p className="text-body font-semibold text-ink">Chưa tải được danh sách món.</p>
        <p className="mt-0.5 text-small text-ink-muted">{detail}</p>
      </div>
      <Button variant="secondary" onClick={onRetry} disabled={wait > 0}>Thử lại</Button>
    </div>
  );
}

function EmptyState({ phrase, showCentre, onCentre, onArea }) {
  return (
    <div className="flex flex-col items-start gap-3 rounded-card border border-line bg-surface p-4 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-body text-ink">Chưa có món nào FoodMap biết {phrase}.</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={onArea}>Chọn khu vực</Button>
        {showCentre && <Button variant="secondary" onClick={onCentre}>Quanh trung tâm TP.HCM</Button>}
      </div>
    </div>
  );
}
