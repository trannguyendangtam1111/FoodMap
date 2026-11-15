import { inSentence, scopeDtoLabel } from '../../utils/scope';
import { t } from '../../i18n';

/**
 * Copy shared by the "Ăn gì hôm nay?" presentations (Master Specification
 * §17). Each presentation names its own action; the meaning is the same.
 */
export const TIFFIN_WORDS = Object.freeze({
  get timeout() { return t('decision.tiffin.timeout'); },
  get failed() { return t('decision.tiffin.failed'); },
  get tooFast() { return t('decision.tiffin.tooFast'); },
});

export const ROLL_WORDS = Object.freeze({
  get timeout() { return t('decision.roll.timeout'); },
  get failed() { return t('decision.roll.failed'); },
  get tooFast() { return t('decision.roll.tooFast'); },
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
      return s > 0 ? t('decision.waitSeconds', { tooFast: words.tooFast, count: s }) : t('decision.canRetry');
    }
    return t('decision.waitMoment', { tooFast: words.tooFast });
  }
  return words.failed;
}

/** Why there is no food: nothing in this scope, or every food already shown. */
export function describeEmpty(empty) {
  const phrase = inSentence(scopeDtoLabel(empty?.scope)) || t('decision.aroundHere');
  return empty?.emptyReason === 'ALL_SHOWN'
    ? t('decision.allShown', { phrase })
    : t('decision.noFood', { phrase });
}
