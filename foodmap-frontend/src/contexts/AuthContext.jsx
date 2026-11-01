import { createContext, useCallback, useEffect, useMemo, useState } from 'react';
import { authApi } from '../api/auth.api';

export const AuthContext = createContext(null);

function decodeUser(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    if (payload.exp * 1000 <= Date.now()) return null;
    // FoodMap JWT carries: sub (email), role, displayName, userId
    return {
      id: payload.userId ?? null,
      email: payload.sub,
      displayName: payload.displayName ?? payload.sub,
      role: payload.role ?? 'USER',
    };
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  // Hydrate from localStorage on first render
  useEffect(() => {
    const token = localStorage.getItem('access_token');
    if (token) setUser(decodeUser(token));
    setIsLoading(false);
  }, []);

  // Listen for the 401 event dispatched by the Axios interceptor
  useEffect(() => {
    const handle = () => setUser(null);
    window.addEventListener('auth:expired', handle);
    return () => window.removeEventListener('auth:expired', handle);
  }, []);

  const storeAuth = useCallback((auth) => {
    localStorage.setItem('access_token', auth.accessToken);
    localStorage.setItem('refresh_token', auth.refreshToken);
    // Prefer the user object from the response body over decoding the JWT
    setUser(auth.user ?? decodeUser(auth.accessToken));
  }, []);

  const login = useCallback(
    async (email, password) => {
      const { data } = await authApi.login({ email, password });
      storeAuth(data);
      return data;
    },
    [storeAuth],
  );

  const register = useCallback(
    async (payload) => {
      const { data } = await authApi.register(payload);
      storeAuth(data);
      return data;
    },
    [storeAuth],
  );

  const logout = useCallback(async () => {
    const refreshToken = localStorage.getItem('refresh_token');
    try {
      if (refreshToken) await authApi.logout(refreshToken);
    } finally {
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      setUser(null);
    }
  }, []);

  const value = useMemo(
    () => ({ user, isAuthenticated: !!user, isLoading, login, register, logout }),
    [user, isLoading, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
