import { LANGUAGES, useI18n } from '../../i18n';

/** Compact EN/VI toggle for the header (desktop and mobile). */
export default function LanguageSwitcher({ className = '' }) {
  const { lang, setLang, t } = useI18n();
  return (
    <div
      role="group"
      aria-label={t('lang.switch')}
      className={`inline-flex flex-shrink-0 items-center rounded-full border border-line bg-surface p-0.5 ${className}`}
    >
      {LANGUAGES.map((code) => {
        const active = lang === code;
        return (
          <button
            key={code}
            type="button"
            lang={code}
            onClick={() => setLang(code)}
            aria-pressed={active}
            aria-label={t(`lang.${code}`)}
            title={t(`lang.${code}`)}
            className={`h-8 min-w-9 rounded-full px-2 text-label font-bold transition-colors duration-fast ${
              active ? 'bg-brand-400 text-ink' : 'text-ink-muted hover:bg-surface-sunken hover:text-ink'
            }`}
          >
            {code.toUpperCase()}
          </button>
        );
      })}
    </div>
  );
}
