package cloud.thesis.model;

public class ScoredRow {
    final String intervalStart;
    final double anomalyScore;
    final boolean isWarning;
    final boolean isCritical;

    ScoredRow(String intervalStart, double anomalyScore, boolean isWarning, boolean isCritical) {
        this.intervalStart = intervalStart;
        this.anomalyScore = anomalyScore;
        this.isWarning = isWarning;
        this.isCritical = isCritical;
    }
}