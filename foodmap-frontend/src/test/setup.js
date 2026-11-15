import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { setLang } from '../i18n';

// The existing suites assert the Vietnamese copy; English (the app default) is
// covered by src/i18n/i18n.test.jsx.
setLang('vi');
beforeEach(() => setLang('vi'));
afterEach(() => cleanup());
