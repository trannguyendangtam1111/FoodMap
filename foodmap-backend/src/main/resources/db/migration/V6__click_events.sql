-- Click events: captures every time a user opens a restaurant detail page
-- or explicitly clicks a restaurant card in search/discovery results.
--
-- Design decisions:
--   - No FK to restaurants or users: analytics tables are insert-heavy;
--     loose coupling avoids lock contention on the restaurants row and lets
--     history survive restaurant soft-deletes or user account deletion.
--   - user_id is nullable: anonymous sessions tracked via session_id only.
--   - search_query stored for CTR analysis (clicks / impressions per query).
--
-- Feeds two future systems:
--   1. Trending job: COUNT(id) WHERE restaurant_id = X AND clicked_at >= window_start
--      → richer signal than view_count, attributable to actual user interest.
--   2. Personalisation: GROUP BY user_id, cuisine_type ORDER BY COUNT DESC
--      → top categories per user → RankingService categoryScore boost.

CREATE TABLE click_events (
    id            BIGSERIAL    PRIMARY KEY,
    restaurant_id BIGINT       NOT NULL,
    user_id       BIGINT,
    session_id    VARCHAR(128),
    search_query  VARCHAR(500),
    clicked_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Hot read path for trending job: all clicks on a restaurant, newest-first.
CREATE INDEX idx_click_events_restaurant_clicked_at
    ON click_events (restaurant_id, clicked_at DESC);

-- Partial index for per-user personalisation queries (skips anonymous rows).
CREATE INDEX idx_click_events_user_id
    ON click_events (user_id)
    WHERE user_id IS NOT NULL;
