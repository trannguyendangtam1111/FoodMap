import { Link } from 'react-router-dom';
import { buttonClass } from '../components/ui/Button';
import Icon from '../components/ui/Icon';
import HeroSection from '../components/home/HeroSection';
import ThemGiSection from '../components/food/ThemGiSection';
import MapTeaser from '../components/home/MapTeaser';

/**
 * Homepage.
 *
 * SAFETY: this page must never mount MapView, load Goong or call
 * /restaurants/viewport or /restaurants/nearby — both queue ingestion.
 * On load it makes exactly one read-only request: "Thèm gì?" asks
 * GET /decisions/foods/available for the current scope (the city centre
 * unless this tab already has the user's location). Everything else is
 * started by the user: the area list (GET /catalog), "Gần tôi" (the browser
 * location prompt), the search box (read-only autocomplete) and "Mở cà mèn"
 * (read-only GET /decisions/food). Location is never asked for on load.
 *
 * "Gần bạn" (nearby rail) is intentionally absent: there is no approved
 * read-only, location-based data source yet. RestaurantTile is ready for it.
 */
export default function HomePage() {
  return (
    <>
      <HeroSection />
      <ThemGiSection />
      <MapTeaser />
      <ClosingBand />
    </>
  );
}

function ClosingBand() {
  return (
    <section aria-labelledby="closing-title" className="mx-auto max-w-6xl px-4 pb-14 pt-6 lg:pb-20">
      <div
        className="flex flex-col items-start gap-5 rounded-panel border-2 border-ink bg-brand-400 px-6 py-8
                   sm:flex-row sm:items-center sm:justify-between sm:px-10"
      >
        <div>
          <h2 id="closing-title" className="font-display text-h2 text-ink">Đói rồi? Đi ăn thôi!</h2>
          <p className="mt-1 text-body text-ink/80">Chọn một quán trên bản đồ và lên đường.</p>
        </div>
        <Link to="/map" className={buttonClass({ variant: 'secondary', size: 'lg', className: 'flex-shrink-0' })}>
          <Icon name="map" size={20} strokeWidth={2} />
          Mở bản đồ
        </Link>
      </div>
    </section>
  );
}
