/**
 * Opening status in Ho Chi Minh City time, computed from the opening-hours
 * map the backend returns: { monday: "06:00-22:00", … }.
 *
 * Rule: only claim open/closed when the data supports it. Missing hours,
 * a missing entry for today, or any value we cannot parse → `unknown`,
 * which the UI renders as nothing at all.
 *
 * Kept dependency-free on purpose (plain ESM) so it can be unit-tested in Node.
 */

const TIME_ZONE = 'Asia/Ho_Chi_Minh';
const DAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
const MINUTES_PER_DAY = 24 * 60;

const UNKNOWN = { state: 'unknown', label: null, detail: null, todayKey: null };

/** Day key + minutes since midnight, as seen on a wall clock in HCMC. */
function hcmcClock(now) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: TIME_ZONE, weekday: 'long', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(now);
  const get = (type) => parts.find((p) => p.type === type)?.value;
  return {
    day: get('weekday').toLowerCase(),
    minutes: Number(get('hour')) * 60 + Number(get('minute')),
  };
}

/**
 * One day's value → list of [open, close] minute ranges.
 * [] means closed all day; null means "could not understand it".
 * Supports "06:00-22:00", several ranges split by "," or ";", ranges that
 * run past midnight ("18:00-02:00"), "24:00" as a closing time, and a few
 * explicit closed / 24-hour phrases.
 */
function parseDay(value) {
  if (typeof value !== 'string') return null;
  const text = value.trim().toLowerCase();
  if (!text) return null;
  if (/^(closed|đóng cửa|nghỉ)$/.test(text)) return [];
  if (/^(24\/7|24h|24 giờ|open 24 hours|mở cả ngày)$/.test(text)) return [[0, MINUTES_PER_DAY]];

  const ranges = [];
  for (const part of text.split(/[,;]/)) {
    const m = /^(\d{1,2}):(\d{2})\s*[-–—]\s*(\d{1,2}):(\d{2})$/.exec(part.trim());
    if (!m) return null;
    const [oh, om, ch, cm] = m.slice(1).map(Number);
    if (oh > 23 || ch > 24 || om > 59 || cm > 59 || (ch === 24 && cm !== 0)) return null;
    const open = oh * 60 + om;
    const close = ch * 60 + cm;
    if (open === close) return null; // ambiguous (closed? 24h?) — don't guess
    ranges.push([open, close]);
  }
  return ranges;
}

const toClock = (minutes) => {
  const m = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
};

/**
 * openingStatus(restaurantOrHours, now?) →
 *   { state: 'open' | 'closed' | 'unknown',
 *     label:  'Đang mở' | 'Đã đóng cửa' | null,
 *     detail: 'đến 22:00' | 'mở lúc 10:00' | null,
 *     todayKey: 'monday' … | null }
 */
export function openingStatus(restaurantOrHours, now = new Date()) {
  // A restaurant DTO (summary DTOs carry no hours at all) or a bare hours map
  const isRestaurant = restaurantOrHours
    && typeof restaurantOrHours === 'object'
    && ('openingHours' in restaurantOrHours || 'id' in restaurantOrHours);
  const hours = isRestaurant ? restaurantOrHours.openingHours : restaurantOrHours;
  if (!hours || typeof hours !== 'object' || Object.keys(hours).length === 0) return UNKNOWN;

  let clock;
  try {
    clock = hcmcClock(now);
  } catch {
    return UNKNOWN;
  }
  const { day, minutes } = clock;
  const yesterday = DAYS[(DAYS.indexOf(day) + 6) % 7];

  const today = day in hours ? parseDay(hours[day]) : undefined;
  const prev  = yesterday in hours ? parseDay(hours[yesterday]) : undefined;
  if (today === null) return { ...UNKNOWN, todayKey: day };

  // Still inside last night's range that runs past midnight?
  for (const [open, close] of prev ?? []) {
    if (close <= open && minutes < close) {
      return { state: 'open', label: 'Đang mở', detail: `đến ${toClock(close)}`, todayKey: day };
    }
  }

  if (today === undefined) return { ...UNKNOWN, todayKey: day };

  for (const [open, close] of today) {
    const overnight = close <= open;
    if (overnight ? minutes >= open : minutes >= open && minutes < close) {
      const allDay = open === 0 && close === MINUTES_PER_DAY;
      return {
        state: 'open', label: 'Đang mở', detail: allDay ? 'mở cả ngày' : `đến ${toClock(close)}`, todayKey: day,
      };
    }
  }

  const nextOpen = today.map(([open]) => open).filter((open) => open > minutes).sort((a, b) => a - b)[0];
  return {
    state: 'closed',
    label: 'Đã đóng cửa',
    detail: nextOpen !== undefined ? `mở lúc ${toClock(nextOpen)}` : null,
    todayKey: day,
  };
}
