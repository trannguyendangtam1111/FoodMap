import { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import AppHeader from './AppHeader';
import Footer from './Footer';
import MobileBottomNav from './MobileBottomNav';

/**
 * Layout for standard scrolling pages: header, page content, footer and the
 * mobile bottom navigation. The map page is full-screen and composes
 * AppHeader + MobileBottomNav itself instead of using this shell.
 */
export default function AppShell() {
  const { pathname } = useLocation();

  // Start each page at the top — BrowserRouter keeps the old scroll position.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);

  return (
    <div className="flex min-h-screen flex-col pb-bottom-nav">
      {/* The homepage hero has its own large search — don't show two */}
      <AppHeader showSearch={pathname !== '/'} />
      <main id="main" className="flex-1">
        <Outlet />
      </main>
      <Footer />
      <MobileBottomNav />
    </div>
  );
}
