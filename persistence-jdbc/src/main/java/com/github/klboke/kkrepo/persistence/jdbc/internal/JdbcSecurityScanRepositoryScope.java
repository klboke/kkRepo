package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.JsonPersistenceDialect;
import java.util.List;

/** Shared SQL fragments for authorization-filtered security-scan repository scopes. */
final class JdbcSecurityScanRepositoryScope {
  private final JsonColumns json;
  private final JsonPersistenceDialect jsonDialect;

  JdbcSecurityScanRepositoryScope(JsonColumns json, JsonPersistenceDialect jsonDialect) {
    this.json = json;
    this.jsonDialect = jsonDialect;
  }

  List<Long> distinctLongs(List<Long> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
  }

  /**
   * Materializes an authorization-filtered repository scope from one JSON bind value. This avoids
   * both one query per repository and database parameter-limit failures for large installations.
   */
  String repositoryScopeCte() {
    return """
        WITH visible_repository AS (
        %s
        )
        """.formatted(jsonDialect.selectLongsFromArray("repository_id"));
  }

  String recursiveRepositoryScopeCte() {
    return """
        WITH RECURSIVE visible_repository AS (
        %s
        )
        """.formatted(jsonDialect.selectLongsFromArray("repository_id"));
  }

  /**
   * Expands visible group policy contexts to their concrete source repositories while retaining
   * direct repository visibility as an unrestricted task scope.
   */
  String taskRepositoryScopeCte() {
    return recursiveRepositoryScopeCte() + """
        ,
        task_policy_source(
          context_repository_id,
          source_repository_id,
          profile_id,
          scan_hosted_content,
          scan_proxy_content
        ) AS (
          SELECT
            config.repository_id,
            config.repository_id,
            config.profile_id,
            config.scan_hosted_content,
            config.scan_proxy_content
          FROM repository_security_scan_config config
          JOIN visible_repository visible
            ON visible.repository_id = config.repository_id
          WHERE config.enabled = TRUE
          UNION
          SELECT
            context.context_repository_id,
            member.member_repository_id,
            context.profile_id,
            context.scan_hosted_content,
            context.scan_proxy_content
          FROM task_policy_source context
          JOIN repository_member member
            ON member.repository_id = context.source_repository_id
        ),
        task_repository_scope(
          source_repository_id,
          profile_id,
          scan_hosted_content,
          scan_proxy_content,
          directly_visible
        ) AS (
          SELECT repository_id, NULL, FALSE, FALSE, TRUE
          FROM visible_repository
          UNION
          SELECT
            source_repository_id,
            profile_id,
            scan_hosted_content,
            scan_proxy_content,
            FALSE
          FROM task_policy_source
          WHERE context_repository_id <> source_repository_id
        )
        """;
  }

  Object parameter(List<Long> repositoryIds) {
    return json.serializedParameter(json.writeValue(distinctLongs(repositoryIds)));
  }
}
