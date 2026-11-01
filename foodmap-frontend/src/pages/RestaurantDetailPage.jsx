import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { restaurantApi } from '../api/restaurant.api';
import ReviewList from '../components/review/ReviewList';
import StarRating from '../components/ui/StarRating';
import Badge from '../components/ui/Badge';
import Chip from '../components/ui/Chip';
import Icon from '../components/ui/Icon';
import Skeleton from '../components/ui/Skeleton';
import { buttonClass } from '../components/ui/Button';
import RestaurantThumb from '../components/restaurant/RestaurantThumb';
import DirectionsLink from '../components/restaurant/DirectionsLink';
import FoodArt from '../components/art/FoodArt';
import useCatalog from '../hooks/useCatalog';
import useLocationScope from '../hooks/useLocationScope';
import { scopeSearch } from '../utils/scope';
import { formatOpeningHours, formatPrice, formatPriceLevel, formatRating } from '../utils/format';
import { categoryOf, isKnownCategory } from '../utils/category';
import { openingStatus } from '../utils/openingStatus';

// Hero height — shared by the page and its loading skeleton
const HERO_CLASS = 'h-44 w-full border-b border-line sm:h-60';

export default function RestaurantDetailPage() {
  const { id } = useParams();
  const [restaurant, setRestaurant] = useState(null);
  const [isLoading, setIsLoading]   = useState(true);
  const [error, setError]           = useState('');

  useEffect(() => {
    setIsLoading(true);
    restaurantApi
      .getById(id)
      .then(({ data }) => setRestaurant(data))
      .catch(() => setError('Không tìm thấy quán này.'))
      .finally(() => setIsLoading(false));
  }, [id]);

  if (isLoading) {
    return <RestaurantDetailSkeleton />;
  }

  if (error || !restaurant) {
    return (
      <div className="flex flex-col items-center gap-4 px-4 py-24 text-center">
        <span className="flex h-14 w-14 items-center justify-center rounded-full bg-brand-50 text-brand-700">
          <Icon name="map-pin" size={28} />
        </span>
        <p className="font-display text-h2 text-ink">{error || 'Không tìm thấy quán này.'}</p>
        <Link to="/map" className={buttonClass()}>
          <Icon name="map" size={18} />
          Về bản đồ
        </Link>
      </div>
    );
  }

  const {
    name, address, phone, website, avgRating, reviewCount, priceLevel,
    openingHours, restaurantFoods,
  } = restaurant;

  const category = categoryOf(restaurant);
  const status   = openingStatus(restaurant);
  const hours    = formatOpeningHours(openingHours);
  const foods    = restaurantFoods?.map((rf) => rf.food).filter(Boolean) ?? [];
  const signature = restaurantFoods?.filter((rf) => rf.signature) ?? [];
  const price    = formatPriceLevel(priceLevel);
  const hasChips = isKnownCategory(category) || price || status.state !== 'unknown';

  return (
    <div className="animate-[fadeIn_0.2s_ease-out]">
      <DetailBackLink />

      {/* Hero — the real photo, or the category illustration when there is none */}
      {/* pb-* keeps the illustration inside the part of the hero the header card doesn't cover */}
      <RestaurantThumb
        restaurant={restaurant}
        category={category}
        pattern
        className={HERO_CLASS}
        artClassName="pb-14 pt-2 sm:pb-16"
      />

      <div className="mx-auto max-w-3xl space-y-5 px-4 pb-16">

        {/* ── Header card ──────────────────────────────────────────────── */}
        <section className="relative -mt-14 rounded-panel border border-line bg-surface p-6 shadow-e2 sm:-mt-16 sm:p-8">
          {hasChips && (
            <div className="mb-3 flex flex-wrap items-center gap-2">
              {isKnownCategory(category) && (
                <Chip tone={category.tone} icon={<Icon name="utensils" size={14} strokeWidth={2} />}>
                  {category.label}
                </Chip>
              )}
              {price && (
                <Chip tone="neutral" aria-label={`Mức giá ${priceLevel} trên 4`}>
                  {price}
                </Chip>
              )}
              {status.state !== 'unknown' && (
                <Badge variant={status.state === 'open' ? 'success' : 'danger'} dot>
                  {status.label}
                  {status.detail && <span className="font-normal">· {status.detail}</span>}
                </Badge>
              )}
            </div>
          )}

          <h1 className="font-display text-h2 text-ink sm:text-h1">{name}</h1>

          {/* Rating — number first; never show empty stars for unrated places */}
          {avgRating > 0 ? (
            <div className="mt-2 flex items-center gap-2">
              <span className="text-body-lg font-bold tabular-nums text-ink">{formatRating(avgRating)}</span>
              <StarRating value={avgRating} size="md" />
              <span className="text-small text-ink-muted">({reviewCount} đánh giá)</span>
            </div>
          ) : (
            <p className="mt-2 text-small text-ink-muted">Chưa có đánh giá — hãy là người đầu tiên!</p>
          )}

          {/* Address / contact */}
          {(address || phone || website) && (
            <ul className="mt-5 space-y-2.5 border-t border-line pt-5 text-body text-ink">
              {address && (
                <ContactRow icon="map-pin">{address}</ContactRow>
              )}
              {phone && (
                <ContactRow icon="phone">
                  <a href={`tel:${phone}`} className="rounded-tag hover:text-brand-700 hover:underline underline-offset-2">
                    {phone}
                  </a>
                </ContactRow>
              )}
              {website && (
                <ContactRow icon="globe">
                  <a
                    href={website}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block truncate rounded-tag hover:text-brand-700 hover:underline underline-offset-2"
                  >
                    {website.replace(/^https?:\/\//, '')}
                  </a>
                </ContactRow>
              )}
            </ul>
          )}

          {/* Go eat: directions to the restaurant's coordinates (hidden without valid ones) */}
          <DirectionsLink restaurant={restaurant} variant="primary" className="mt-5 w-full sm:w-auto" />
        </section>

        {/* ── The foods FoodMap knows here (server classification) ─────── */}
        <FoodContext foodKeys={restaurant.foodKeys} />

        {/* ── Signature dishes ─────────────────────────────────────────── */}
        {signature.length > 0 && (
          <DetailSection title="Món đặc trưng">
            <div className="grid gap-3 sm:grid-cols-2">
              {signature.map((rf) => (
                <div
                  key={rf.id}
                  className="flex items-start justify-between gap-3 rounded-card border border-brand-100 bg-brand-50 px-4 py-3"
                >
                  <div className="min-w-0">
                    <p className="text-body font-semibold text-ink">{rf.food.name}</p>
                    {rf.food.description && (
                      <p className="mt-0.5 line-clamp-2 text-small text-ink-muted">{rf.food.description}</p>
                    )}
                  </div>
                  {rf.price && (
                    <span className="flex-shrink-0 text-body font-semibold tabular-nums text-brand-800">
                      {formatPrice(rf.price)}
                    </span>
                  )}
                </div>
              ))}
            </div>
          </DetailSection>
        )}

        {/* ── Full menu ────────────────────────────────────────────────── */}
        {restaurantFoods?.length > 0 && (
          <DetailSection title="Thực đơn">
            <ul className="-my-1 divide-y divide-line">
              {restaurantFoods.map((rf) => (
                <li key={rf.id} className="flex items-center justify-between gap-4 py-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="text-body font-medium text-ink">{rf.food.name}</p>
                      {rf.signature && <Badge variant="brand">Đặc trưng</Badge>}
                    </div>
                    {rf.food.description && (
                      <p className="mt-0.5 line-clamp-1 text-small text-ink-muted">{rf.food.description}</p>
                    )}
                  </div>
                  {rf.price && (
                    <span className="flex-shrink-0 text-body tabular-nums text-ink">{formatPrice(rf.price)}</span>
                  )}
                </li>
              ))}
            </ul>
          </DetailSection>
        )}

        {/* ── Opening hours ─────────────────────────────────────────────── */}
        {hours.length > 0 && (
          <DetailSection title="Giờ mở cửa">
            <ul className="-my-1 divide-y divide-line">
              {hours.map(({ key, day, hours: h }) => {
                const isToday = key === status.todayKey;
                return (
                  <li
                    key={key}
                    aria-current={isToday ? 'date' : undefined}
                    className={`-mx-3 flex justify-between rounded-field px-3 py-2.5 text-body
                                ${isToday ? 'bg-brand-50 font-semibold' : ''}`}
                  >
                    <span className="flex items-center gap-2 text-ink">
                      {day}
                      {isToday && <Badge variant="brand">Hôm nay</Badge>}
                    </span>
                    <span className={`tabular-nums ${isToday ? 'text-ink' : 'text-ink-muted'}`}>{h}</span>
                  </li>
                );
              })}
            </ul>
          </DetailSection>
        )}

        {/* ── Reviews ──────────────────────────────────────────────────── */}
        <section className="rounded-tile border border-line bg-surface p-5 shadow-e1 sm:p-6">
          <ReviewList
            restaurantId={id}
            avgRating={avgRating}
            reviewCount={reviewCount}
            foods={foods}
          />
        </section>
      </div>
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

// The shared AppHeader provides logo + navigation; this is just the way back.
// Arriving from inside the app (homepage suggestion, search, map), it goes
// back to exactly where the user was. Opened directly (shared link, new tab:
// no in-app history, React Router's "default" location key), it offers the map.
function DetailBackLink() {
  const navigate = useNavigate();
  const { key } = useLocation();
  const className = `-ml-3 inline-flex min-h-11 items-center gap-1 rounded-full px-3 text-small font-semibold
                     text-ink-muted transition-colors duration-fast hover:bg-surface-sunken hover:text-ink`;
  const content = (label) => (
    <>
      <Icon name="chevron-left" size={18} strokeWidth={2} />
      {label}
    </>
  );

  return (
    <div className="mx-auto max-w-3xl px-4 py-1">
      {key !== 'default'
        ? <button type="button" onClick={() => navigate(-1)} className={className}>{content('Quay lại')}</button>
        : <Link to="/map" className={className}>{content('Về bản đồ')}</Link>}
    </div>
  );
}

function ContactRow({ icon, children }) {
  return (
    <li className="flex items-start gap-3">
      <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-brand-50 text-brand-700">
        <Icon name={icon} size={16} />
      </span>
      <span className="min-w-0 flex-1 pt-1">{children}</span>
    </li>
  );
}

/**
 * "Quán này có" — the foods the server matched for this restaurant
 * (`foodKeys`, from its name and FoodMap menu; name matches first), each
 * linking to its food page. Labels and art come from the server's catalog;
 * only discoverable foods are listed (the others have no food page). Hidden
 * when there is none — nothing is inferred here.
 *
 * Arriving from a food page (router state `fromFood`, set by its restaurant
 * links), that food comes first and is named — only when the server's
 * foodKeys contain it. Links carry the current scope (?area= / ?near=1),
 * never coordinates.
 */
function FoodContext({ foodKeys }) {
  const keys = Array.isArray(foodKeys) ? foodKeys : [];
  const catalogState = useCatalog({ enabled: keys.length > 0 });
  const { scope } = useLocationScope();
  const fromKey = useLocation().state?.fromFood;

  const catalog = catalogState.status === 'ready' ? catalogState.catalog : null;
  const foods = catalog
    ? keys.map((key) => catalog.foods.find((f) => f.key === key && f.discoverable)).filter(Boolean)
    : [];
  if (foods.length === 0) return null;

  const from = foods.find((f) => f.key === fromKey) ?? null;
  const ordered = from ? [from, ...foods.filter((f) => f !== from)] : foods;
  const search = scopeSearch(scope);

  return (
    <DetailSection title="Quán này có">
      <p className="-mt-2 mb-3 text-small text-ink-muted">
        {from
          ? `Bạn đến từ món ${from.label.toLocaleLowerCase('vi')} · theo tên quán và thực đơn trên FoodMap.`
          : 'Theo tên quán và thực đơn trên FoodMap.'}
      </p>
      <ul className="flex flex-wrap gap-2">
        {ordered.map((food) => (
          <li key={food.key}>
            <Link
              to={`/mon/${encodeURIComponent(food.key)}${search}`}
              aria-current={food === from ? 'true' : undefined}
              className={`group inline-flex min-h-11 items-center gap-2 rounded-full border py-1 pl-1 pr-4
                          text-small font-semibold text-ink transition-colors duration-fast ease-standard
                          hover:border-brand-300 hover:bg-brand-50
                          ${food === from ? 'border-brand-400 bg-brand-50' : 'border-line bg-surface'}`}
            >
              <FoodArt
                artKey={food.artKey}
                parentCategory={food.parentCategory}
                tone={food.tone}
                className="h-9 w-9 flex-shrink-0 rounded-full border border-line"
              />
              {food.label}
            </Link>
          </li>
        ))}
      </ul>
    </DetailSection>
  );
}

function DetailSection({ title, children }) {
  return (
    <section className="rounded-tile border border-line bg-surface p-5 shadow-e1 sm:p-6">
      <h2 className="mb-4 font-display text-h3 text-ink">{title}</h2>
      {children}
    </section>
  );
}

function RestaurantDetailSkeleton() {
  return (
    <div aria-busy="true" aria-label="Đang tải thông tin quán">
      <DetailBackLink />
      <Skeleton className={`${HERO_CLASS} rounded-none`} />

      <div className="mx-auto max-w-3xl space-y-5 px-4 pb-16">
        {/* Header card */}
        <div className="relative -mt-14 space-y-3 rounded-panel border border-line bg-surface p-6 shadow-e2 sm:-mt-16 sm:p-8">
          <Skeleton className="h-7 w-24 rounded-full" />
          <Skeleton className="h-8 w-2/3" />
          <Skeleton className="h-4 w-40" />
          <div className="space-y-2.5 border-t border-line pt-5">
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="h-4 w-1/3" />
          </div>
        </div>

        {/* Dishes */}
        <div className="space-y-4 rounded-tile border border-line bg-surface p-5 sm:p-6">
          <Skeleton className="h-5 w-32" />
          <div className="grid gap-3 sm:grid-cols-2">
            {Array.from({ length: 2 }).map((_, i) => (
              <Skeleton key={i} className="h-16 rounded-card" />
            ))}
          </div>
        </div>

        {/* Reviews */}
        <div className="space-y-4 rounded-tile border border-line bg-surface p-5 sm:p-6">
          <Skeleton className="h-5 w-24" />
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="flex gap-3">
              <Skeleton className="h-9 w-9 flex-shrink-0 rounded-full" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-3.5 w-1/3" />
                <Skeleton className="h-3 w-full" />
                <Skeleton className="h-3 w-4/5" />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
