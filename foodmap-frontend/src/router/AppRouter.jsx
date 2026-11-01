import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import ProtectedRoute from './ProtectedRoute';
import AppShell from '../components/layout/AppShell';
import Spinner from '../components/ui/Spinner';
import HomePage from '../pages/HomePage';
import RestaurantDetailPage from '../pages/RestaurantDetailPage';
import FoodPage from '../pages/FoodPage';
import LoginPage from '../pages/LoginPage';
import RegisterPage from '../pages/RegisterPage';
import NotFoundPage from '../pages/NotFoundPage';

// The map page is the only route that loads Goong GL. Lazy-loading it keeps
// the map library out of every other route — "/" in particular must never
// initialise the map, because its viewport request queues ingestion.
const MapPage = lazy(() => import('../pages/MapPage'));

export default function AppRouter() {
  return (
    <Routes>
      {/* Standard pages: header + content + footer + mobile bottom nav */}
      <Route element={<AppShell />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/restaurants/:id" element={<RestaurantDetailPage />} />
        <Route path="/mon/:foodKey" element={<FoodPage />} />

        {/* Protected routes — add future authenticated pages here */}
        <Route
          path="/profile"
          element={
            <ProtectedRoute>
              {/* ProfilePage placeholder */}
              <p className="px-4 py-24 text-center text-body text-ink-muted">Trang cá nhân sắp ra mắt.</p>
            </ProtectedRoute>
          }
        />

        <Route path="*" element={<NotFoundPage />} />
      </Route>

      {/* Full-screen map — composes the header and bottom nav itself */}
      <Route
        path="/map"
        element={
          <Suspense
            fallback={
              <div className="flex h-screen items-center justify-center">
                <Spinner size="lg" />
              </div>
            }
          >
            <MapPage />
          </Suspense>
        }
      />

      {/* Focused auth pages — no shell */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
    </Routes>
  );
}
