import { useEffect, useId, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import Button, { buttonClass } from '../components/ui/Button';
import Icon from '../components/ui/Icon';
import Skeleton from '../components/ui/Skeleton';
import FoodArt from '../components/art/FoodArt';
import ScopeBar from '../components/location/ScopeBar';
import AreaPicker from '../components/location/AreaPicker';
import RetryNotice from '../components/food/RetryNotice';
import { radiusText } from '../components/food/FoodChip';
import FoodRestaurantSuggestion from '../components/restaurant/FoodRestaurantSuggestion';
import FoodRestaurantList, { FoodRestaurantListSkeleton } from '../components/restaurant/FoodRestaurantList';
import useCatalog from '../hooks/useCatalog';
import useLocationScope from '../hooks/useLocationScope';
import useFoodRestaurants from '../hooks/useFoodRestaurants';
import { CITY_CENTRE_SCOPE, MODES, inSentence, scopeDtoLabel, scopeLabel } from '../utils/scope';
import { t, useI18n } from '../i18n';

/**
 * /mon/:foodKey[?area=key | ?near=1] — the food page: one FOOD, then the
 * restaurants FoodMap knows to have it, then a restaurant (/restaurants/:id).
 *
 * Everything shown is the server's: the food (GET /catalog: label, art key —
 * an unknown key is "Chưa có món này", never another food), the suggestion
 * (GET /decisions/today?food=), the ranked list and its count
 * (GET /decisions/foods/{key}/restaurants — the same count "Thèm gì?" shows),
 * each restaurant's category and the evidence for the food. Nothing is
 * classified, ranked or drawn here.
 *
 * Scope comes from the URL — ?area=key (AREA), ?near=1 (USER), nothing (the
 * city centre) — never coordinates. ?near=1 uses the location already granted
 * in this tab; without one the page asks where to look and requests location
 * only when "Gần tôi" is pressed, never on load. Choices made here update the
 * URL (replace) and the shared scope store, so "Thèm gì?" and the cà mèn agree.
 *
 * Never mounts the map, never calls a provider or triggers ingestion.
 */
/**
 * The list's count in full: "127 quán", or "N+ quán" when the server says
 * its total is a lower bound (totalCapped) — never inferred from the number.
 */
export const totalText = (total, atLeast) => (atLeast ? t('food.countAtLeast', { count: total }) : t('food.count', { count: total }));

const optionOf = (mode) => (mode === MODES.USER ? 'near' : mode === MODES.AREA ? 'area' : 'centre');

export default function FoodPage() {
  const { lang } = useI18n();
  const { foodKey } = useParams();
  const [params, setParams] = useSearchParams();
  const catalogState = useCatalog({ enabled: true });
  const { scope: storeScope, location, selectCityCentre, selectArea, chooseNearMe } = useLocationScope();
  const [picking, setPicking] = useState(false);
  const [locating, setLocating] = useState(false);
  const [notice, setNotice] = useState(null);
  const ids = { picker: useId(), suggestion: useId(), list: useId() };
  const scopeBarRef = useRef(null);
  const changeAreaRef = useRef(null);
  const focusAfterPick = useRef(false);
  const focusOption = useRef(null);   // a ScopeBar radio to focus once it is there
  const promptAreaRef = useRef(null);
  const locateAbort = useRef(null);

  useEffect(() => () => locateAbort.current?.abort(), []);

  const catalog = catalogState.status === 'ready' ? catalogState.catalog : null;
  const food = catalog?.foods.find((f) => f.key === foodKey && f.discoverable) ?? null;
  const areaKey = params.get('area');
  const near = !areaKey && params.get('near') === '1';
  const area = areaKey && catalog ? catalog.areas.find((a) => a.key === areaKey) ?? null : null;

  // The scope this page shows, from its URL (+ this tab's location for ?near=1).
  const scope = areaKey
    ? (area ? { mode: MODES.AREA, areaKey: area.key, areaLabel: area.label } : null)
    : near
      ? (location ? { mode: MODES.USER, location } : null)
      : CITY_CENTRE_SCOPE;

  const view = !catalog
    ? catalogState.status === 'error' ? 'catalogError' : 'loading'
    : !food ? 'unknownFood'
      : areaKey && !area ? 'badArea'
        : near && !location ? 'prompt'
          : 'results';

  const data = useFoodRestaurants(foodKey, scope, { enabled: view === 'results' });

  // A shared link or reload: the store follows the URL (never the other way round).
  useEffect(() => {
    if (view !== 'results') return;
    if (scope.mode === MODES.AREA && (storeScope.mode !== MODES.AREA || storeScope.areaKey !== scope.areaKey)) {
      selectArea(scope.areaKey, scope.areaLabel);
    } else if (scope.mode === MODES.USER && storeScope.mode !== MODES.USER) {
      chooseNearMe();   // the location is already in this tab: no prompt
    }
  }, [view, scope?.mode, scope?.areaKey]); // eslint-disable-line react-hooks/exhaustive-deps

  // A button that changed the scope (prompt, empty state) is gone with that
  // state: keep keyboard users in place on the matching ScopeBar radio.
  useEffect(() => {
    if (!focusOption.current) return;
    const input = scopeBarRef.current?.querySelector(`input[value="${focusOption.current}"]`);
    if (input) {
      if (!input.contains(document.activeElement)) input.focus({ preventScroll: true });
      focusOption.current = null;
    }
  });

  // After choosing an area, hand focus to "Đổi khu vực" (the list closed under it).
  useEffect(() => {
    if (focusAfterPick.current && areaKey && !picking) {
      focusAfterPick.current = false;
      changeAreaRef.current?.focus({ preventScroll: true });
    }
  }, [areaKey, picking]);

  const goCentre = () => {
    setNotice(null);
    setPicking(false);
    setParams({}, { replace: true });
    selectCityCentre();
    focusOption.current = 'centre';
  };

  const goNear = async () => {
    setNotice(null);
    setPicking(false);
    locateAbort.current?.abort();
    const controller = new AbortController();
    locateAbort.current = controller;
    setLocating(true);
    // The explicit action that may show the browser prompt (none when this tab has a location).
    const ok = await chooseNearMe({ signal: controller.signal });
    if (controller.signal.aborted) return;
    setLocating(false);
    if (ok) {
      setParams({ near: '1' }, { replace: true });
      focusOption.current = 'near';
    } else setNotice('themgi.notice.locationFailed');   // i18n key
  };

  const openPicker = () => {
    setNotice(null);
    setPicking(true);
  };

  const onPickArea = (picked) => {
    focusAfterPick.current = true;
    setPicking(false);
    setNotice(null);
    setParams({ area: picked.key }, { replace: true });
    selectArea(picked.key, picked.label);
  };

  const closePicker = () => {
    setPicking(false);
    if (view === 'prompt') {
      promptAreaRef.current?.focus({ preventScroll: true });
      return;
    }
    const option = areaKey ? 'area' : near ? 'near' : 'centre';
    scopeBarRef.current?.querySelector(`input[value="${option}"]`)?.focus({ preventScroll: true });
  };

  const onScopeChange = (option) => {
    if (option === 'area') openPicker();
    else if (option === 'centre') goCentre();
    else goNear();
  };

  if (view === 'loading') return <PageSkeleton />;

  if (view === 'catalogError') {
    return (
      <Shell>
        <h1 className="font-display text-h1 text-ink">{t('themgi.loadFailed')}</h1>
        <RetryNotice title={t('foodPage.cantOpen')} error={catalogState.error} onRetry={catalogState.retry} className="mt-4" />
      </Shell>
    );
  }

  if (view === 'unknownFood' || data.list.error?.status === 404) {
    return (
      <Shell>
        <h1 className="font-display text-h1 text-ink">{t('foodPage.unknown')}</h1>
        <Link to="/" className={buttonClass({ variant: 'secondary', className: 'mt-5' })}>{t('notFound.home')}</Link>
      </Shell>
    );
  }

  // Inside a Vietnamese sentence the food reads in lower case; English keeps the server's label.
  const foodLower = lang === 'vi' ? food.label.toLocaleLowerCase('vi') : food.label;
  const list = data.list;
  const listData = list.status === 'ready' ? list.data : null;
  const label = scopeDtoLabel(listData?.scope) ?? (scope ? scopeLabel(scope) : null);
  const phrase = inSentence(label);
  const scopeRefused = view === 'results'
    && (list.error?.status === 400 || data.suggestion.error?.status === 400);
  const empty = listData && listData.totalElements === 0;
  // None among the rows the server read is not "none at all" when its read was capped.
  const emptyText = listData?.totalCapped
    ? t('foodPage.emptyCapped', { phrase, food: foodLower })
    : t('foodPage.empty', { food: foodLower, phrase });
  const selected = locating ? 'near' : picking ? 'area' : optionOf(scope?.mode ?? (near ? MODES.USER : MODES.AREA));

  const status = view === 'prompt'
    ? t('foodPage.where', { food: foodLower })
    : view === 'badArea'
      ? t('foodPage.badArea')
      : scopeRefused
        ? t('foodPage.scopeRefused')
        : list.status === 'loading'
          ? t('foodPage.searching', { food: foodLower, phrase })
          : list.status === 'error'
            ? t('foodPage.listFailed')
            : empty
              ? emptyText
              : listData ? t('foodPage.statusTotal', { total: totalText(listData.totalElements, listData.totalCapped), food: foodLower, phrase }) : '';

  return (
    <Shell>
      <header className="flex items-center gap-4 sm:gap-5">
        <FoodArt
          artKey={food.artKey}
          parentCategory={food.parentCategory}
          tone={food.tone}
          className="h-20 w-20 flex-shrink-0 rounded-tile border-2 border-ink shadow-sticker-soft sm:h-24 sm:w-24"
        />
        <div className="min-w-0">
          <h1 className="break-words font-display text-h1 text-ink">{food.label}</h1>
          {label && (
            <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-small font-semibold text-brand-800">
              <Icon name="map-pin" size={16} strokeWidth={2} />
              <span>{label}</span>
              {areaKey && area && !picking && view === 'results' && (
                <button
                  ref={changeAreaRef}
                  type="button"
                  aria-expanded={false}
                  aria-controls={ids.picker}
                  onClick={openPicker}
                  className="inline-flex min-h-11 items-center rounded-full px-2 text-small font-semibold text-brand-700
                             underline decoration-brand-300 underline-offset-4 hover:text-brand-800"
                >
                  {t('themgi.changeArea')}
                </button>
              )}
            </p>
          )}
        </div>
      </header>

      {view === 'prompt' ? (
        <div className="mt-6 rounded-card border border-line bg-surface p-4 sm:p-5">
          <h2 className="font-display text-h3 text-ink">{t('foodPage.where', { food: foodLower })}</h2>
          <p className="mt-1 text-small text-ink-muted">{t('foodPage.locationNote')}</p>
          <div className="mt-4 flex flex-col gap-2 sm:flex-row sm:flex-wrap">
            <Button variant="secondary" onClick={goNear} isLoading={locating} className="w-full sm:w-auto">{t('scope.option.near')}</Button>
            <button
              ref={promptAreaRef}
              type="button"
              onClick={openPicker}
              aria-expanded={picking}
              aria-controls={ids.picker}
              className={buttonClass({ variant: 'secondary', className: 'w-full sm:w-auto' })}
            >
              {t('themgi.chooseArea')}
            </button>
            <Button variant="primary" onClick={goCentre} className="w-full sm:w-auto">{t('scope.cityCentre')}</Button>
          </div>
        </div>
      ) : (
        <div ref={scopeBarRef} className="mt-5">
          <ScopeBar value={selected} onChange={onScopeChange} busy={locating} legend={t('foodPage.scopeLegend', { food: foodLower })} />
        </div>
      )}

      {picking && (
        <AreaPicker id={ids.picker} selectedKey={areaKey} onSelect={onPickArea} onClose={closePicker} />
      )}

      {notice && (
        <p className="mt-3 rounded-tag bg-warning-tint px-3 py-2 text-small font-medium text-warning-ink">{t(notice)}</p>
      )}

      {view === 'badArea' && (
        <ScopeProblem text={t('foodPage.badArea')} onCentre={goCentre} onArea={openPicker} />
      )}

      {view === 'results' && scopeRefused && (
        <ScopeProblem
          text={scope.mode === MODES.AREA ? t('foodPage.areaUnavailable') : t('foodPage.locationUnusable')}
          onCentre={goCentre}
          onArea={openPicker}
        />
      )}

      {view === 'results' && !scopeRefused && empty && (
        <div className="mt-6 flex flex-col items-start gap-3 rounded-card border border-line bg-surface p-4 sm:p-5">
          <p className="text-body text-ink">{emptyText}</p>
          <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:flex-wrap">
            <Link to="/" className={buttonClass({ variant: 'secondary', className: 'w-full sm:w-auto' })}>{t('decision.anotherDish')}</Link>
            <Button variant="secondary" onClick={openPicker} className="w-full sm:w-auto">{t('foodPage.otherArea')}</Button>
            {scope.mode === MODES.CITY_CENTER
              ? <Button variant="secondary" onClick={goNear} isLoading={locating} className="w-full sm:w-auto">{t('scope.option.near')}</Button>
              : <Button variant="secondary" onClick={goCentre} className="w-full sm:w-auto">{t('scope.cityCentre')}</Button>}
          </div>
        </div>
      )}

      {view === 'results' && !scopeRefused && !empty && (
        <>
          {listData?.scope.radiusWidened && (
            <p className="mt-4 flex items-start gap-1.5 text-small text-ink-muted">
              <Icon name="globe" size={16} className="mt-0.5 flex-shrink-0" />
              <span>{t('foodPage.widened', { label, food: foodLower, radius: radiusText(listData.scope.radiusMeters) })}</span>
            </p>
          )}
          <div className="mt-6 grid gap-8 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)] lg:items-start">
            <FoodRestaurantSuggestion
              state={data.suggestion}
              foodKey={food.key}
              foodLabel={food.label}
              phrase={phrase}
              shownCount={data.shownCount}
              headingId={ids.suggestion}
              onMore={data.more}
              onRestart={data.restart}
              onRetry={data.retrySuggestion}
            />
            <section aria-labelledby={ids.list} aria-busy={list.status === 'loading' || undefined} className="min-w-0">
              <h2 id={ids.list} className="break-words font-display text-h3 text-ink">{t('foodPage.listTitle', { food: foodLower, phrase })}</h2>
              <p className="mt-1 text-small text-ink-muted">
                {listData ? `${totalText(listData.totalElements, listData.totalCapped)} · ` : ''}{t('themgi.countNote')}
              </p>
              <div className="mt-3">
                {list.status === 'loading' && <FoodRestaurantListSkeleton />}
                {list.status === 'error' && (
                  <RetryNotice title={t('foodPage.listFailed')} error={list.error} onRetry={data.retryList} />
                )}
                {listData && (
                  <FoodRestaurantList
                    items={listData.items}
                    foodKey={food.key}
                    foodLabel={food.label}
                    labelledBy={ids.list}
                    hasMore={list.hasMore}
                    loadingMore={list.loadingMore}
                    moreError={list.moreError}
                    onLoadMore={data.loadMore}
                  />
                )}
              </div>
            </section>
          </div>
        </>
      )}

      <p role="status" aria-live="polite" aria-atomic="true" className="sr-only">{status}</p>
    </Shell>
  );
}

function ScopeProblem({ text, onCentre, onArea }) {
  useI18n();
  return (
    <div className="mt-6 flex flex-col items-start gap-3 rounded-card border border-line bg-surface p-4 sm:p-5">
      <p className="text-body text-ink">{text}</p>
      <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:flex-wrap">
        <Button variant="secondary" onClick={onCentre} className="w-full sm:w-auto">{t('scope.cityCentre')}</Button>
        <Button variant="secondary" onClick={onArea} className="w-full sm:w-auto">{t('themgi.chooseArea')}</Button>
      </div>
    </div>
  );
}

function PageSkeleton() {
  useI18n();
  return (
    <Shell busy>
      <div className="flex items-center gap-4 sm:gap-5">
        <Skeleton className="h-20 w-20 flex-shrink-0 rounded-tile sm:h-24 sm:w-24" />
        <span className="min-w-0 flex-1">
          <Skeleton className="h-9 w-40" />
          <Skeleton className="mt-3 h-4 w-56 max-w-full" />
        </span>
      </div>
      <Skeleton className="mt-5 h-11 w-72 max-w-full rounded-full" />
      <div className="mt-6"><FoodRestaurantListSkeleton /></div>
      <p role="status" className="sr-only">{t('foodPage.loading')}</p>
    </Shell>
  );
}

function Shell({ children, busy = false }) {
  return (
    <section aria-busy={busy || undefined} className="mx-auto max-w-6xl px-4 py-8 lg:py-12">
      {children}
    </section>
  );
}
