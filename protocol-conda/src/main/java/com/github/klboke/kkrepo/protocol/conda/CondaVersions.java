package com.github.klboke.kkrepo.protocol.conda;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conda VersionOrder-compatible parsing and comparison. */
public final class CondaVersions {
  private static final Pattern VALID = Pattern.compile("^[*.+!_0-9a-z]+$");
  private static final Pattern TOKEN = Pattern.compile("([0-9]+|[*]+|[^0-9*]+)");
  public static final Comparator<String> COMPARATOR = CondaVersions::compare;

  private CondaVersions() {
  }

  public static String require(String value) {
    parse(value);
    return value;
  }

  public static int compare(String left, String right) {
    Parsed a = parse(left);
    Parsed b = parse(right);
    int main = compareComponents(a.version(), b.version());
    return main != 0 ? main : compareComponents(a.local(), b.local());
  }

  private static Parsed parse(String raw) {
    if (raw == null) throw invalid(raw);
    String value = raw.strip().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.length() > CondaPackageIdentifiers.MAX_VERSION_LENGTH) {
      throw invalid(raw);
    }
    if (!VALID.matcher(value).matches() && value.indexOf('-') >= 0 && value.indexOf('_') < 0) {
      value = value.replace('-', '_');
    }
    if (!VALID.matcher(value).matches()) throw invalid(raw);

    String[] epochParts = value.split("!", -1);
    if (epochParts.length > 2) throw invalid(raw);
    String epoch = "0";
    String versionAndLocal = epochParts[0];
    if (epochParts.length == 2) {
      if (!digits(epochParts[0])) throw invalid(raw);
      epoch = epochParts[0];
      versionAndLocal = epochParts[1];
    }
    String[] localParts = versionAndLocal.split("\\+", -1);
    if (localParts.length > 2 || localParts[0].isEmpty()) throw invalid(raw);
    List<List<Token>> local = localParts.length == 2
        ? components(localParts[1].replace('_', '.').split("\\.", -1), raw)
        : List.of();

    String main = localParts[0];
    String[] mainParts;
    if (main.endsWith("_")) {
      String without = main.substring(0, main.length() - 1).replace('_', '.');
      mainParts = without.split("\\.", -1);
      if (mainParts.length == 0 || mainParts[mainParts.length - 1].isEmpty()) throw invalid(raw);
      mainParts[mainParts.length - 1] += "_";
    } else {
      mainParts = main.replace('_', '.').split("\\.", -1);
    }
    ArrayList<String> withEpoch = new ArrayList<>(mainParts.length + 1);
    withEpoch.add(epoch);
    java.util.Collections.addAll(withEpoch, mainParts);
    return new Parsed(components(withEpoch.toArray(String[]::new), raw), local);
  }

  private static List<List<Token>> components(String[] rawComponents, String original) {
    ArrayList<List<Token>> result = new ArrayList<>(rawComponents.length);
    for (String component : rawComponents) {
      if (component.isEmpty()) throw invalid(original);
      ArrayList<Token> tokens = new ArrayList<>();
      Matcher matcher = TOKEN.matcher(component);
      while (matcher.find()) {
        String token = matcher.group(1);
        if (digits(token)) tokens.add(new NumericToken(new BigInteger(token), false));
        else if ("post".equals(token)) tokens.add(NumericToken.POSITIVE_INFINITY);
        else if ("dev".equals(token)) tokens.add(new StringToken("DEV"));
        else tokens.add(new StringToken(token));
      }
      if (tokens.isEmpty()) throw invalid(original);
      if (!Character.isDigit(component.charAt(0))) {
        tokens.addFirst(NumericToken.ZERO);
      }
      result.add(List.copyOf(tokens));
    }
    return List.copyOf(result);
  }

  private static int compareComponents(
      List<List<Token>> left, List<List<Token>> right) {
    int componentCount = Math.max(left.size(), right.size());
    for (int i = 0; i < componentCount; i++) {
      List<Token> a = i < left.size() ? left.get(i) : List.of();
      List<Token> b = i < right.size() ? right.get(i) : List.of();
      int tokenCount = Math.max(a.size(), b.size());
      for (int j = 0; j < tokenCount; j++) {
        Token x = j < a.size() ? a.get(j) : NumericToken.ZERO;
        Token y = j < b.size() ? b.get(j) : NumericToken.ZERO;
        int compared = compareToken(x, y);
        if (compared != 0) return compared;
      }
    }
    return 0;
  }

  private static int compareToken(Token left, Token right) {
    if (left instanceof StringToken a) {
      if (!(right instanceof StringToken b)) return -1;
      return a.value().compareTo(b.value());
    }
    if (right instanceof StringToken) return 1;
    NumericToken a = (NumericToken) left;
    NumericToken b = (NumericToken) right;
    if (a.infinity() || b.infinity()) return Boolean.compare(a.infinity(), b.infinity());
    return a.value().compareTo(b.value());
  }

  private static boolean digits(String value) {
    return value != null && !value.isEmpty()
        && value.chars().allMatch(Character::isDigit);
  }

  private static IllegalArgumentException invalid(String value) {
    return new IllegalArgumentException("Invalid Conda version: " + value);
  }

  private sealed interface Token permits NumericToken, StringToken { }

  private record NumericToken(BigInteger value, boolean infinity) implements Token {
    private static final NumericToken ZERO = new NumericToken(BigInteger.ZERO, false);
    private static final NumericToken POSITIVE_INFINITY = new NumericToken(BigInteger.ZERO, true);
  }

  private record StringToken(String value) implements Token { }

  private record Parsed(List<List<Token>> version, List<List<Token>> local) { }
}
