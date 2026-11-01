import Skeleton from '../ui/Skeleton';

// Mirrors RestaurantCard: 64px thumb, name, meta line, address.
export default function RestaurantCardSkeleton() {
  return (
    <div className="flex gap-3 rounded-card border border-line bg-surface p-2.5 shadow-e1">
      <Skeleton className="h-16 w-16 flex-shrink-0 rounded-field" />
      <div className="flex-1 space-y-2 py-1">
        <Skeleton className="h-4 w-3/4" />
        <Skeleton className="h-3 w-1/2" />
        <Skeleton className="h-3 w-2/3" />
      </div>
    </div>
  );
}
