import { Link } from 'react-router-dom';
import { buttonClass } from '../components/ui/Button';
import { useI18n } from '../i18n';

export default function NotFoundPage() {
  const { t } = useI18n();
  return (
    <section className="mx-auto flex max-w-md flex-col items-center px-4 py-20 text-center sm:py-28">
      <p aria-hidden="true" className="font-display text-[5rem] font-black leading-none text-brand-600">404</p>
      <h1 className="mt-4 font-display text-h2 text-ink">{t('notFound.title')}</h1>
      <p className="mt-2 text-body text-ink-muted">
        {t('notFound.body')}
      </p>
      <div className="mt-8 flex w-full flex-col gap-3 sm:w-auto sm:flex-row">
        <Link to="/" className={buttonClass()}>{t('notFound.home')}</Link>
        <Link to="/map" className={buttonClass({ variant: 'secondary' })}>{t('notFound.map')}</Link>
      </div>
    </section>
  );
}
