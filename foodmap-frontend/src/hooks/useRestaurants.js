import { useCallback, useEffect, useState } from 'react';
import { restaurantApi } from '../api/restaurant.api';

export function useRestaurants(center) {
  const [restaurants, setRestaurants] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchNearby = useCallback(async (lat, lng, radius = 1500) => {
    setIsLoading(true);
    setError(null);
    try {
      const { data } = await restaurantApi.getNearby({ lat, lng, radius });
      // Backend returns a Page object: { content, totalElements, … }
      setRestaurants(data?.content ?? []);
    } catch (err) {
      setError(err.response?.data?.message ?? 'Failed to load restaurants.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!center) return;
    fetchNearby(center.lat, center.lng);
  }, [center?.lat, center?.lng, fetchNearby]);

  return { restaurants, isLoading, error, refetch: fetchNearby };
}
