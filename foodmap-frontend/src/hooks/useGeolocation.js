import { useEffect, useState } from 'react';

// Ho Chi Minh City centre — used as fallback when the browser denies location
const HCMC_DEFAULT = { lat: 10.7769, lng: 106.7009 };

/**
 * location    { lat, lng } | null — real position, or the HCMC fallback
 * isFallback  true when `location` is the city-centre fallback, not the user.
 *             Never present a distance "from you" when this is true.
 */
export function useGeolocation() {
  const [location, setLocation] = useState(null);
  const [isFallback, setIsFallback] = useState(false);
  const [error, setError] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    if (!navigator.geolocation) {
      setError('Geolocation is not supported by this browser.');
      setLocation(HCMC_DEFAULT);
      setIsFallback(true);
      setIsLoading(false);
      return;
    }

    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setIsLoading(false);
      },
      () => {
        // Permission denied or unavailable — fall back silently
        setLocation(HCMC_DEFAULT);
        setIsFallback(true);
        setIsLoading(false);
      },
      { timeout: 8000 },
    );
  }, []);

  return { location, isFallback, error, isLoading };
}
