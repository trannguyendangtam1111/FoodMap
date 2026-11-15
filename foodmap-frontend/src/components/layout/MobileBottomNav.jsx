import { Link, NavLink } from 'react-router-dom';
import Icon from '../ui/Icon';
import { useI18n } from '../../i18n';

const tabClass = ({ isActive }) =>
  `flex h-16 flex-col items-center justify-center gap-0.5 text-label transition-colors duration-fast ${
    isActive ? 'text-brand-800' : 'text-ink-muted hover:text-ink'
  }`;

/**
 * Fixed bottom navigation, mobile only (hidden from md up).
 * Pages using it must reserve space with the `pb-bottom-nav` utility.
 *
 * The raised centre slot is "Ăn gì hôm nay?": it jumps to the cà mèn on the
 * homepage (/#an-gi).
 */
export default function MobileBottomNav() {
  const { t } = useI18n();
  return (
    <nav
      aria-label={t('layout.nav')}
      className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-surface/95
                 pb-[env(safe-area-inset-bottom)] backdrop-blur-sm md:hidden"
    >
      <ul className="mx-auto grid max-w-md grid-cols-3">
        <li>
          <NavLink to="/" end className={tabClass}>
            <Icon name="home" size={22} />
            {t('layout.home')}
          </NavLink>
        </li>

        <li className="flex justify-center">
          <Link
            to={{ pathname: '/', hash: '#an-gi' }}
            aria-label={t('layout.whatToEatToday')}
            className="relative -mt-6 flex flex-col items-center gap-1"
          >
            <span
              className="flex h-14 w-14 items-center justify-center rounded-full border-2 border-ink
                         bg-brand-400 text-ink shadow-sticker"
            >
              <Icon name="tiffin" size={26} />
            </span>
            <span className="text-label text-ink">{t('layout.whatToEat')}</span>
          </Link>
        </li>

        <li>
          <NavLink to="/map" className={tabClass}>
            <Icon name="map" size={22} />
            {t('layout.map')}
          </NavLink>
        </li>
      </ul>
    </nav>
  );
}
