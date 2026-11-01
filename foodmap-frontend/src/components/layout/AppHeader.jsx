import { Link, NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import SiteSearch from '../search/SiteSearch';
import Icon from '../ui/Icon';
import { buttonClass } from '../ui/Button';
import Logo from './Logo';

const navLinkClass = ({ isActive }) =>
  `rounded-full px-3.5 py-2 text-small font-semibold transition-colors duration-fast ${
    isActive
      ? 'bg-brand-50 text-brand-800'
      : 'text-ink-muted hover:bg-surface-sunken hover:text-ink'
  }`;

/**
 * Global header.
 *
 * Props
 * ─────
 * search      node  Page-specific search control. The map page passes its own
 *                   SearchBar wired to map state; every other page gets
 *                   SiteSearch (foods → /mon/{key}, other text → /map).
 * showSearch  bool  false on pages that carry their own prominent search
 *                   (the homepage hero), to avoid two search fields.
 * fluid       bool  Full-width content (map page) instead of the centred container.
 */
export default function AppHeader({ search, showSearch = true, fluid = false }) {
  return (
    <header className="sticky top-0 z-30 flex-shrink-0 border-b border-line bg-cream/90 backdrop-blur-sm">
      <a
        href="#main"
        className="sr-only rounded-full bg-surface px-4 py-2 text-small font-semibold text-ink shadow-e2
                   focus:not-sr-only focus:fixed focus:left-4 focus:top-3 focus:z-50"
      >
        Bỏ qua đến nội dung
      </a>

      <div className={`mx-auto flex h-16 items-center gap-3 px-4 md:gap-4 ${fluid ? '' : 'max-w-6xl'}`}>
        <Logo compact className="text-xl" />

        <div className="min-w-0 flex-1 md:max-w-md">
          {showSearch && (search ?? <SiteSearch />)}
        </div>

        <nav aria-label="Điều hướng chính" className="ml-auto hidden items-center gap-1 md:flex">
          <NavLink to="/map" className={navLinkClass}>Bản đồ</NavLink>
          <DecisionLink />
        </nav>

        <AccountArea />
      </div>
    </header>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

/** "Ăn gì hôm nay?" — jumps to the cà mèn on the homepage (/#an-gi). */
function DecisionLink() {
  return (
    <Link
      to={{ pathname: '/', hash: '#an-gi' }}
      className="inline-flex items-center gap-1.5 rounded-full border-2 border-brand-300
                 px-3 py-1.5 text-small font-semibold text-brand-800
                 transition-colors duration-fast hover:bg-brand-50"
    >
      <Icon name="tiffin" size={18} />
      <span className="lg:hidden">Ăn gì?</span>
      <span className="hidden lg:inline">Ăn gì hôm nay?</span>
    </Link>
  );
}

function AccountArea() {
  const { isAuthenticated, user, logout } = useAuth();

  if (!isAuthenticated) {
    return (
      <div className="flex flex-shrink-0 items-center gap-1 md:gap-2">
        {/* Mobile: one icon link */}
        <Link
          to="/login"
          aria-label="Đăng nhập"
          className="flex h-11 w-11 items-center justify-center rounded-full text-ink-muted
                     hover:bg-surface-sunken hover:text-ink md:hidden"
        >
          <Icon name="user" size={22} />
        </Link>
        <Link to="/login" className={buttonClass({ variant: 'ghost', size: 'sm', className: 'hidden md:inline-flex' })}>
          Đăng nhập
        </Link>
        <Link to="/register" className={buttonClass({ size: 'sm', className: 'hidden md:inline-flex' })}>
          Đăng ký
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-shrink-0 items-center gap-2">
      <span
        aria-hidden="true"
        className="flex h-9 w-9 items-center justify-center rounded-full bg-brand-100
                   font-display text-body font-black text-brand-800"
      >
        {user.displayName?.[0]?.toUpperCase() ?? '?'}
      </span>
      <span className="hidden max-w-[140px] truncate text-small font-semibold text-ink lg:block">
        {user.displayName}
      </span>
      <button
        type="button"
        onClick={logout}
        aria-label="Đăng xuất"
        className="flex h-11 min-w-11 items-center justify-center gap-1.5 rounded-full px-2.5 text-small font-semibold text-ink-muted
                   transition-colors duration-fast hover:bg-surface-sunken hover:text-ink"
      >
        <Icon name="logout" size={20} />
        <span className="hidden md:inline">Đăng xuất</span>
      </button>
    </div>
  );
}
