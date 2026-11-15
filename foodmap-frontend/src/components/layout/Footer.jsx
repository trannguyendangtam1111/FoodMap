import { Link } from 'react-router-dom';
import Logo from './Logo';
import { useI18n } from '../../i18n';

export default function Footer() {
  const { t } = useI18n();
  return (
    <footer className="border-t border-line bg-surface-sunken/60">
      <div className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-8 md:flex-row md:items-start md:justify-between">
        <div>
          <Logo className="text-xl" />
          <p className="mt-2 text-small text-ink-muted">{t('layout.footerTagline')}</p>
        </div>

        <nav aria-label={t('layout.footerLinks')}>
          <ul className="flex gap-6 text-small font-semibold text-ink">
            <li><Link to="/" className="-my-3.5 inline-block rounded-tag py-3.5 hover:text-brand-700">{t('layout.home')}</Link></li>
            <li><Link to="/map" className="-my-3.5 inline-block rounded-tag py-3.5 hover:text-brand-700">{t('layout.map')}</Link></li>
          </ul>
        </nav>
      </div>

      <div className="border-t border-line">
        {/* Extra bottom room on mobile: the raised centre button of the bottom
            nav rises above the nav bar and would otherwise cover this row. */}
        <div className="mx-auto flex max-w-6xl flex-wrap justify-between gap-2 px-4 pb-10 pt-4 text-small text-ink-muted md:pb-4">
          <span>© {new Date().getFullYear()} FoodMap</span>
          <span>{t('layout.mapData')}</span>
        </div>
      </div>
    </footer>
  );
}
