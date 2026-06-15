import * as React from "react";
import type { ReactElement } from "react";
import {
  UNSAFE_LocationContext as LocationContext,
  UNSAFE_RouteContext as RouteContext,
  useLocation,
  useNavigationType,
  useOutlet
} from "react-router-dom";

interface CacheEntry {
  key: string;
  element: ReactElement;
  locationContext: React.ContextType<typeof LocationContext>;
  routeContext: React.ContextType<typeof RouteContext>;
  updatedAt: number;
}

interface CachedOutletProps {
  maxEntries?: number;
}

const DEFAULT_MAX_ENTRIES = 8;

function getCacheKey(pathname: string, search: string) {
  return `${pathname}${search}`;
}

export function CachedOutlet({ maxEntries = DEFAULT_MAX_ENTRIES }: CachedOutletProps) {
  const outlet = useOutlet();
  const location = useLocation();
  const navigationType = useNavigationType();
  const liveLocationContext = React.useContext(LocationContext);
  const liveRouteContext = React.useContext(RouteContext);
  const cacheRef = React.useRef<CacheEntry[]>([]);
  const cacheKey = getCacheKey(location.pathname, location.search);

  if (outlet) {
    const existing = cacheRef.current.find((entry) => entry.key === cacheKey);
    if (existing) {
      existing.updatedAt = Date.now();
    } else {
      cacheRef.current = [
        ...cacheRef.current,
        {
          key: cacheKey,
          element: outlet,
          locationContext: {
            location,
            navigationType
          },
          routeContext: liveRouteContext,
          updatedAt: Date.now()
        }
      ];
    }
  }

  if (cacheRef.current.length > maxEntries) {
    cacheRef.current = cacheRef.current
      .sort((left, right) => {
        if (left.key === cacheKey) return -1;
        if (right.key === cacheKey) return 1;
        return right.updatedAt - left.updatedAt;
      })
      .slice(0, maxEntries);
  }

  return (
    <>
      {cacheRef.current.map((entry) => {
        const active = entry.key === cacheKey;
        const locationContext = active ? liveLocationContext : entry.locationContext;
        const routeContext = active ? liveRouteContext : entry.routeContext;

        return (
          <div
            key={entry.key}
            style={{ display: active ? undefined : "none" }}
            aria-hidden={active ? undefined : true}
          >
            <LocationContext.Provider value={locationContext}>
              <RouteContext.Provider value={routeContext}>
                {entry.element}
              </RouteContext.Provider>
            </LocationContext.Provider>
          </div>
        );
      })}
    </>
  );
}
