import { Link, NavLink } from 'react-router-dom';
import Icon from '../ui/Icon';

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
  return (
    <nav
      aria-label="Điều hướng"
      className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-surface/95
                 pb-[env(safe-area-inset-bottom)] backdrop-blur-sm md:hidden"
    >
      <ul className="mx-auto grid max-w-md grid-cols-3">
        <li>
          <NavLink to="/" end className={tabClass}>
            <Icon name="home" size={22} />
            Trang chủ
          </NavLink>
        </li>

        <li className="flex justify-center">
          <Link
            to={{ pathname: '/', hash: '#an-gi' }}
            aria-label="Ăn gì hôm nay?"
            className="relative -mt-6 flex flex-col items-center gap-1"
          >
            <span
              className="flex h-14 w-14 items-center justify-center rounded-full border-2 border-ink
                         bg-brand-400 text-ink shadow-sticker"
            >
              <Icon name="tiffin" size={26} />
            </span>
            <span className="text-label text-ink">Ăn gì?</span>
          </Link>
        </li>

        <li>
          <NavLink to="/map" className={tabClass}>
            <Icon name="map" size={22} />
            Bản đồ
          </NavLink>
        </li>
      </ul>
    </nav>
  );
}
