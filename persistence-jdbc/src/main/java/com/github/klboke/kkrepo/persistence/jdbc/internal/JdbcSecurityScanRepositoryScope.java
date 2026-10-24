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

  /**
   * Shared definition of a current finding association for overview and finding queries.
   * The alias is an internal SQL identifier, never request input. Do not restrict scan_state:
   * the published latest run remains authoritative while a same-content rescan is pending.
   */
  String currentFindingSubjectPredicate(String subject) {
    return """
        EXISTS (
          SELECT 1
          FROM asset_security_state current_state
          JOIN security_scan_candidate current_candidate
            ON current_candidate.asset_id = current_state.asset_id
           AND current_candidate.content_generation = current_state.content_generation
          WHERE current_state.asset_id = %1$s.asset_id
            AND current_state.profile_id = %1$s.profile_id
            AND current_state.content_generation = %1$s.content_generation
            AND current_state.latest_scan_run_id = %1$s.scan_run_id
        )
        """.formatted(subject);
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
