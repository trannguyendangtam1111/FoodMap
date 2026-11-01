import { Link } from 'react-router-dom';
import Logo from './Logo';

export default function Footer() {
  return (
    <footer className="border-t border-line bg-surface-sunken/60">
      <div className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-8 md:flex-row md:items-start md:justify-between">
        <div>
          <Logo className="text-xl" />
          <p className="mt-2 text-small text-ink-muted">Khám phá món ngon quanh bạn.</p>
        </div>

        <nav aria-label="Liên kết chân trang">
          <ul className="flex gap-6 text-small font-semibold text-ink">
            <li><Link to="/" className="-my-3.5 inline-block rounded-tag py-3.5 hover:text-brand-700">Trang chủ</Link></li>
            <li><Link to="/map" className="-my-3.5 inline-block rounded-tag py-3.5 hover:text-brand-700">Bản đồ</Link></li>
          </ul>
        </nav>
      </div>

      <div className="border-t border-line">
        {/* Extra bottom room on mobile: the raised centre button of the bottom
            nav rises above the nav bar and would otherwise cover this row. */}
        <div className="mx-auto flex max-w-6xl flex-wrap justify-between gap-2 px-4 pb-10 pt-4 text-small text-ink-muted md:pb-4">
          <span>© {new Date().getFullYear()} FoodMap</span>
          <span>Dữ liệu bản đồ: Goong</span>
        </div>
      </div>
    </footer>
  );
}
