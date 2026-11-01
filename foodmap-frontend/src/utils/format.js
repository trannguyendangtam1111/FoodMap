/**
 * Format a VND price. Backend stores prices as BigDecimal in VND (e.g. 65000).
 * Output: "65.000 ₫"
 */
export function formatPrice(value) {
  if (value == null) return '';
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(value);
}

/**
 * Translate the 1-4 priceLevel integer into a visual indicator.
 */
export function formatPriceLevel(level) {
  const labels = { 1: '₫', 2: '₫₫', 3: '₫₫₫', 4: '₫₫₫₫' };
  return labels[level] ?? '';
}

/**
 * Format an average rating with one decimal, Vietnamese style: 4.5 → "4,5".
 */
export function formatRating(value) {
  return Number(value ?? 0).toLocaleString('vi-VN', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  });
}

/**
 * Return a short relative time string.
 * Falls back to a locale date string when the Intl.RelativeTimeFormat API
 * is unavailable (older browsers).
 */
export function formatRelativeTime(isoString) {
  if (!isoString) return '';
  const date = new Date(isoString);
  const diffMs = Date.now() - date.getTime();
  const diffSec = Math.floor(diffMs / 1000);

  if (typeof Intl?.RelativeTimeFormat === 'undefined') {
    return date.toLocaleDateString('vi-VN');
  }

  const rtf = new Intl.RelativeTimeFormat('vi', { numeric: 'auto' });

  if (diffSec < 60) return rtf.format(-diffSec, 'second');
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return rtf.format(-diffMin, 'minute');
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return rtf.format(-diffHr, 'hour');
  const diffDay = Math.floor(diffHr / 24);
  if (diffDay < 30) return rtf.format(-diffDay, 'day');
  const diffMonth = Math.floor(diffDay / 30);
  if (diffMonth < 12) return rtf.format(-diffMonth, 'month');
  return rtf.format(-Math.floor(diffMonth / 12), 'year');
}

/**
 * Format opening hours map into a human-readable list.
 * Input: { monday: "06:00-22:00", … }
 */
export function formatOpeningHours(hoursMap) {
  if (!hoursMap) return [];
  const order = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
  const dayLabels = { monday: 'Thứ 2', tuesday: 'Thứ 3', wednesday: 'Thứ 4', thursday: 'Thứ 5', friday: 'Thứ 6', saturday: 'Thứ 7', sunday: 'Chủ nhật' };
  return order
    .filter((d) => hoursMap[d])
    .map((d) => ({ key: d, day: dayLabels[d], hours: hoursMap[d] }));
}
