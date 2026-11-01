package com.foodmap.ingestion.snapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The human-readable companion (.md) of an AUTO run report. Plain text, no secrets (the store redacts it too). */
final class AutoReportMarkdown {

    private static final int TOP_CANDIDATES = 15;

    private AutoReportMarkdown() {
    }

    static String render(SnapshotRunReport r) {
        AutoRunReport a = r.getAuto();
        AutoRunReport.Config c = a.getConfig();
        StringBuilder md = new StringBuilder();
        md.append("# AUTO snapshot run ").append(r.getRunId()).append("\n\n");
        md.append("- Mode: **").append(r.getMode()).append("**, stop: **").append(r.getStopReason()).append("**\n");
        if (r.getStopDetail() != null) md.append("- Detail: ").append(r.getStopDetail()).append("\n");
        md.append("- Started: ").append(r.getStartedAt()).append(" — finished: ").append(r.getFinishedAt()).append("\n");
        md.append("- Database: ").append(nz(c.getDatabase())).append("\n");
        md.append("- Budget: configured ").append(c.getConfiguredBudget()).append(", effective ").append(c.getEffectiveBudget())
            .append(" (daily ceiling ").append(c.getDailyCeiling()).append(", retry margin ").append(c.getRetryMargin()).append(")\n");
        md.append("- Settings: target ").append(c.getTarget()).append(", min-score ").append(c.getMinScore())
            .append(", wave-size ").append(c.getWaveSize()).append(", max-probes ").append(c.getMaxProbes())
            .append(", excluded ").append(c.getExcludeFoods())
            .append(c.getAreas().isEmpty() ? ", all areas" : ", areas " + c.getAreas()).append("\n");
        if (a.getLedgerBefore() != null) {
            md.append("- Ledger before: ").append(a.getLedgerBefore().getUsage24h()).append(" used in 24 h, ")
                .append(a.getLedgerBefore().getCompletedPairs()).append(" completed pairs, pending ")
                .append(a.getLedgerBefore().getPendingPairs()).append("\n");
        }
        if (a.getLedgerAfter() != null) {
            md.append("- Ledger after: ").append(a.getLedgerAfter().getUsage24h()).append(" used in 24 h, ")
                .append(a.getLedgerAfter().getCompletedPairs()).append(" completed pairs, pending ")
                .append(a.getLedgerAfter().getPendingPairs()).append("\n");
        }
        SnapshotRunReport.Requests q = r.getRequests();
        md.append("- Requests: ").append(q.getUsed()).append(" (Autocomplete ").append(q.getAutocomplete())
            .append(", Place Detail ").append(q.getPlaceDetail()).append(", retries ").append(q.getRetries())
            .append(", failures ").append(q.getFailureCount()).append(")\n");
        md.append("- New restaurants: ").append(r.getNewRestaurants().size()).append(", rejected ").append(r.getRejected().size())
            .append(", duplicates ").append(r.getDuplicates()).append(", pending pairs ").append(r.getPartialCells().size()).append("\n");
        if (a.getEstimatedMaxRequests() != null) {
            md.append("- Dry-run estimate: at most ").append(a.getEstimatedMaxRequests()).append(" requests, expected ~")
                .append(a.getEstimatedExpectedRequests()).append("; projected 24 h usage ").append(a.getProjectedLedgerUsage()).append("\n");
        }

        List<AutoRunReport.Wave> waves = a.getWaves().isEmpty() ? a.getPlannedWaves() : a.getWaves();
        md.append("\n## ").append(a.getWaves().isEmpty() ? "Planned waves (dry run)" : "Waves").append("\n\n");
        if (waves.isEmpty()) md.append("_none_\n");
        for (AutoRunReport.Wave w : waves) {
            md.append("### Wave ").append(w.getIndex()).append(" — ").append(w.getArea()).append("\n\n");
            md.append(w.getReason()).append("\n\n");
            if (w.getRequestsUsed() != null) {
                md.append("Requests ").append(w.getRequestsUsed()).append(", stop ").append(w.getStopReason())
                    .append(", DB check ").append(w.getDbCheck()).append("\n\n");
            }
            md.append("| Pair | Tier | Count | Score | AC | PD | New | Relevant | Inside | Rejected | Pending |\n");
            md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (AutoRunReport.WaveProbe p : w.getProbes()) {
                AutoRunReport.Candidate pl = p.getPlanned();
                md.append("| ").append(pl.getPair()).append(" | ").append(nz(pl.getTier())).append(" | ").append(pl.getCount())
                    .append(" | ").append(pl.getScore()).append(" | ").append(p.getAutocomplete()).append(" | ").append(p.getPlaceDetail())
                    .append(" | ").append(p.getNewRestaurants()).append(" | ").append(p.getRelevant()).append(" | ")
                    .append(p.getInsideRelevant()).append(" | ").append(p.getRejected()).append(" | ").append(p.getPending()).append(" |\n");
            }
            md.append("\n");
        }

        md.append("## Coverage\n\n");
        line(md, "Pairs reaching target", a.getPairsReachedTarget());
        line(md, "Spill-over", a.getSpillOver());

        md.append("\n## Top candidates at the start\n\n");
        md.append("| # | Pair | Tier | Count | Expected useful | Overlap | Neighbours | Cost | Score |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        int i = 0;
        for (AutoRunReport.Candidate cand : a.getCandidates()) {
            if (++i > TOP_CANDIDATES) break;
            md.append("| ").append(i).append(" | ").append(cand.getPair()).append(" | ").append(cand.getTier()).append(" | ")
                .append(cand.getCount()).append(" | ").append(cand.getExpectedUseful()).append(" | ").append(cand.getOverlapDiscount())
                .append(" | ").append(cand.getNeighbourMultiplier()).append(" | ").append(cand.getExpectedCost()).append(" | ")
                .append(cand.getScore()).append(" |\n");
        }
        md.append("\n").append(a.getCandidates().size()).append(" eligible pair(s); skipped: ");
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (AutoRunReport.Skipped s : a.getSkipped()) {
            String key = s.getReason().replaceAll("\\d+(\\.\\d+)?", "#");
            reasons.merge(key, 1, Integer::sum);
        }
        md.append(reasons.isEmpty() ? "none" : reasons.toString()).append("\n");
        return md.toString();
    }

    private static void line(StringBuilder md, String label, List<AutoRunReport.CoverageChange> changes) {
        md.append("- ").append(label).append(": ");
        if (changes.isEmpty()) {
            md.append("none\n");
            return;
        }
        md.append(String.join(", ", changes.stream()
            .map(ch -> ch.getFood() + "@" + ch.getArea() + " " + ch.getBefore() + "→" + ch.getAfter()).toList())).append("\n");
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }
}
