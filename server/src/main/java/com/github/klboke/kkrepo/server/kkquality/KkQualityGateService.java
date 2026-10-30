package com.github.klboke.kkrepo.server.kkquality;

import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Analysis;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Measure;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.QualityGate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Local, deterministic quality-gate evaluation.
 *
 * <p>Gate conditions are stored as {@code [{"metric","operator","value"}]}. Evaluation reads the
 * measures of the latest completed analysis of the branch, so a gate result is reproducible from
 * durable state and never depends on a replica-local cache. Only one global default gate can exist;
 * the DAO enforces that when a default is installed.
 */
@Service
public class KkQualityGateService {
  private final KkQualityDao dao;

  public KkQualityGateService(KkQualityDao dao) {
    this.dao = dao;
  }

  public record Condition(
      String status, String metricKey, String comparator, String errorThreshold,
      String actualValue) {}

  public record ProjectStatus(String status, List<Condition> conditions, String analysisId) {}

  public ProjectStatus evaluate(long projectId, long branchId) {
    Optional<Analysis> latest = dao.findLatestAnalysis(projectId, branchId)
        .filter(analysis -> "COMPLETE".equals(analysis.status()));
    Optional<QualityGate> gate = dao.findQualityGateForProject(projectId);
    if (gate.isEmpty()) {
      return new ProjectStatus("NONE", List.of(), latest.map(Analysis::analysisId).orElse(null));
    }
    Map<String, BigDecimal> measures = measuresOf(latest);
    List<Condition> conditions = new ArrayList<>();
    boolean failed = false;
    for (Map<String, Object> condition : gate.get().conditions()) {
      String metric = asText(condition.get("metric"));
      if (metric == null) {
        continue;
      }
      String operator = asText(condition.get("operator"));
      BigDecimal threshold = asDecimal(condition.get("value"));
      BigDecimal actual = measures.get(metric);
      String comparator = comparator(operator);
      boolean breached = breached(operator, actual, threshold);
      failed |= breached;
      conditions.add(new Condition(
          breached ? "ERROR" : "OK", metric, comparator,
          threshold == null ? null : threshold.stripTrailingZeros().toPlainString(),
          actual == null ? null : actual.stripTrailingZeros().toPlainString()));
    }
    return new ProjectStatus(
        failed ? "ERROR" : "OK", conditions, latest.map(Analysis::analysisId).orElse(null));
  }

  /** Measures of an analysis as a metric → value map. Text-valued measures are ignored. */
  public Map<String, BigDecimal> measuresOf(Optional<Analysis> analysis) {
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    if (analysis.isEmpty()) {
      return values;
    }
    for (Measure measure : dao.listMeasures(analysis.get().id())) {
      if (measure.valueNum() != null) {
        values.put(measure.metricKey(), measure.valueNum());
      }
    }
    return values;
  }

  private static boolean breached(String operator, BigDecimal actual, BigDecimal threshold) {
    if (actual == null || threshold == null) {
      return false;
    }
    return switch (operator == null ? "" : operator.toUpperCase(java.util.Locale.ROOT)) {
      case "GREATER_THAN", "GT" -> actual.compareTo(threshold) > 0;
      case "LESS_THAN", "LT" -> actual.compareTo(threshold) < 0;
      case "EQUALS", "EQ" -> actual.compareTo(threshold) == 0;
      case "NOT_EQUALS", "NE" -> actual.compareTo(threshold) != 0;
      default -> false;
    };
  }

  private static String comparator(String operator) {
    return switch (operator == null ? "" : operator.toUpperCase(java.util.Locale.ROOT)) {
      case "GREATER_THAN", "GT" -> "GT";
      case "LESS_THAN", "LT" -> "LT";
      case "EQUALS", "EQ" -> "EQ";
      case "NOT_EQUALS", "NE" -> "NE";
      default -> "";
    };
  }

  private static String asText(Object value) {
    return value == null ? null : value.toString();
  }

  private static BigDecimal asDecimal(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return new BigDecimal(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
