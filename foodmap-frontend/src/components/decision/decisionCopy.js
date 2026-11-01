import { inSentence } from '../../utils/scope';

/**
 * Copy shared by the "Ăn gì hôm nay?" presentations (Master Specification
 * §17). Each presentation names its own action; the meaning is the same.
 */
export const TIFFIN_WORDS = Object.freeze({
  timeout: 'Cà mèn mở hơi lâu. Thử lại nhé.',
  failed: 'Chưa mở được cà mèn. Thử lại nhé.',
  tooFast: 'Bạn mở nhanh quá',
});

export const ROLL_WORDS = Object.freeze({
  timeout: 'Roll chạy hơi lâu. Thử lại nhé.',
  failed: 'Roll chưa chạy được. Thử lại nhé.',
  tooFast: 'Bạn bấm nhanh quá',
});

/**
 * What went wrong, in words a person can act on. Never claims "no food" for
 * a network/server problem — that's EMPTY, a real answer.
 */
export function describeDecisionError(error, waitSeconds = null, words = TIFFIN_WORDS) {
  if (error?.name === 'DecisionTimeoutError') return words.timeout;
  if (error?.status === 429) {
    if (Number.isFinite(error.retryAfterSeconds) && error.retryAfterSeconds > 0) {
      const s = waitSeconds ?? error.retryAfterSeconds;
      return s > 0 ? `${words.tooFast}, đợi ${s} giây nhé.` : 'Giờ bạn thử lại được rồi.';
    }
    return `${words.tooFast}, đợi một chút nhé.`;
  }
  return words.failed;
}

/** Why there is no food: nothing in this scope, or every food already shown. */
export function describeEmpty(empty) {
  const phrase = inSentence(empty?.scope?.label) || 'quanh đây';
  return empty?.emptyReason === 'ALL_SHOWN'
    ? `Bạn đã xem hết các món ${phrase}.`
    : `Chưa có món nào FoodMap biết ${phrase}.`;
}
