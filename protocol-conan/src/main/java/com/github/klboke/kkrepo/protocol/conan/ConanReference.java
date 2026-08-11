package com.github.klboke.kkrepo.protocol.conan;

import com.github.klboke.kkrepo.core.DatabaseCompositeKey;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical Conan 2 recipe/package identity independent of an HTTP route. */
public record ConanReference(
    String name,
    String version,
    String user,
    String channel,
    String recipeRevision,
    String packageId,
    String packageRevision) {
  private static final Pattern REFERENCE_PART =
      Pattern.compile("[a-z0-9_][a-z0-9_+.-]{1,100}");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9_+.-]{1,128}");
  private static final Pattern PACKAGE_ID = Pattern.compile("[A-Za-z0-9_+.-]{1,128}");

  public ConanReference {
    requirePart(name, "name");
    requirePart(version, "version");
    requireOptionalPart(user, "user");
    requireOptionalPart(channel, "channel");
    if (channel != null && user == null) {
      throw new IllegalArgumentException("Conan channel requires a user");
    }
    requireOptional(recipeRevision, REVISION, "recipe revision");
    requireOptional(packageId, PACKAGE_ID, "package id");
    requireOptional(packageRevision, REVISION, "package revision");
    if (packageId != null && recipeRevision == null) {
      throw new IllegalArgumentException("Conan package id requires a recipe revision");
    }
    if (packageRevision != null && packageId == null) {
      throw new IllegalArgumentException("Conan package revision requires a package id");
    }
    if (recipeString(name, version, user, channel).length() > 200) {
      throw new IllegalArgumentException("Conan recipe reference exceeds 200 characters");
    }
  }

  public ConanReference recipeRevision(String value) {
    return new ConanReference(name, version, user, channel, value, packageId, packageRevision);
  }

  public ConanReference packageCoordinate(String id, String revision) {
    return new ConanReference(name, version, user, channel, recipeRevision, id, revision);
  }

  public String recipe() {
    return recipeString(name, version, user, channel);
  }

  public String recipeWithRevision() {
    return recipeRevision == null ? recipe() : recipe() + "#" + recipeRevision;
  }

  public String packageReference() {
    if (packageId == null) return null;
    String result = recipeWithRevision() + ":" + packageId;
    return packageRevision == null ? result : result + "#" + packageRevision;
  }

  public String routeUser() {
    return user == null ? "_" : user;
  }

  public String routeChannel() {
    return channel == null ? "_" : channel;
  }

  public String namespace() {
    return routeUser() + "/" + routeChannel();
  }

  /** Stable, collision-checked database identity input. */
  public String coordinateKey() {
    return DatabaseCompositeKey.of(name, version, user, channel);
  }

  private static String recipeString(
      String name, String version, String user, String channel) {
    String result = name + "/" + version;
    if (user != null) result += "@" + user;
    if (channel != null) result += "/" + channel;
    return result;
  }

  private static void requirePart(String value, String field) {
    if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))
        || !REFERENCE_PART.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Conan " + field + ": " + value);
    }
  }

  private static void requireOptionalPart(String value, String field) {
    if (value != null) requirePart(value, field);
  }

  private static void requireOptional(String value, Pattern pattern, String field) {
    if (value != null && !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Conan " + field + ": " + value);
    }
  }
}
