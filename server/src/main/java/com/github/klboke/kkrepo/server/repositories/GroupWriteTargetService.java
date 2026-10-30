package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Resolves the hosted repository that receives writes sent to a group URL.
 *
 * <p>Groups stay read-only for clients unless an operator explicitly sets
 * {@code group.writeRepository}. When unset, the first hosted member in group order is used. The
 * target must be a hosted repository of the same format and a direct group member. Authorization
 * is evaluated against the target by {@code RepositorySecurityFilter}.
 */
@Service
public class GroupWriteTargetService {
  private final RepositoryDao repositories;

  public GroupWriteTargetService(RepositoryDao repositories) {
    this.repositories = repositories;
  }

  public Optional<RepositoryRuntime> writeTarget(RepositoryRuntime group) {
    if (group == null || !group.isGroup()) {
      return Optional.ofNullable(group);
    }
    String configured = configuredTarget(group.id());
    if (configured != null) {
      return findRuntime(group.members(), configured);
    }
    return firstHosted(group.members());
  }

  public RepositoryRuntime requireWriteTarget(RepositoryRuntime group) {
    return writeTarget(group).orElseThrow(() -> new MavenExceptions.MethodNotAllowed(
        "Group repository has no hosted member that can receive writes: " + group.name()));
  }

  public Optional<RepositoryRecord> writeTargetRecord(RepositoryRecord group) {
    if (group == null || group.type() != RepositoryType.GROUP) {
      return Optional.ofNullable(group);
    }
    String configured = configuredTarget(group.id());
    if (configured != null) {
      return findRecord(group.id(), configured, new HashSet<>());
    }
    return firstHostedRecord(group.id(), new HashSet<>());
  }

  private Optional<RepositoryRuntime> findRuntime(
      List<RepositoryRuntime> members, String name) {
    for (RepositoryRuntime member : members) {
      if (member.name().equals(name)) {
        return member.isHosted() ? Optional.of(member) : Optional.empty();
      }
      if (member.isGroup()) {
        Optional<RepositoryRuntime> nested = findRuntime(member.members(), name);
        if (nested.isPresent()) return nested;
      }
    }
    return Optional.empty();
  }

  private Optional<RepositoryRuntime> firstHosted(List<RepositoryRuntime> members) {
    for (RepositoryRuntime member : members) {
      if (member.isHosted()) return Optional.of(member);
      if (member.isGroup()) {
        Optional<RepositoryRuntime> nested = firstHosted(member.members());
        if (nested.isPresent()) return nested;
      }
    }
    return Optional.empty();
  }

  private Optional<RepositoryRecord> findRecord(
      long groupId, String name, Set<Long> visited) {
    if (!visited.add(groupId)) return Optional.empty();
    for (RepositoryRecord member : repositories.listMembers(groupId)) {
      if (member.name().equals(name)) {
        return member.type() == RepositoryType.HOSTED
            ? Optional.of(member) : Optional.empty();
      }
      if (member.type() == RepositoryType.GROUP) {
        Optional<RepositoryRecord> nested = findRecord(member.id(), name, visited);
        if (nested.isPresent()) return nested;
      }
    }
    return Optional.empty();
  }

  private Optional<RepositoryRecord> firstHostedRecord(long groupId, Set<Long> visited) {
    if (!visited.add(groupId)) return Optional.empty();
    for (RepositoryRecord member : repositories.listMembers(groupId)) {
      if (member.type() == RepositoryType.HOSTED) return Optional.of(member);
      if (member.type() == RepositoryType.GROUP) {
        Optional<RepositoryRecord> nested = firstHostedRecord(member.id(), visited);
        if (nested.isPresent()) return nested;
      }
    }
    return Optional.empty();
  }

  private String configuredTarget(long groupId) {
    return repositories.findById(groupId)
        .map(RepositoryRecord::attributes)
        .map(attributes -> attributes == null ? null : attributes.get("group"))
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(group -> group.get("writeRepository"))
        .filter(java.util.Objects::nonNull)
        .map(Object::toString)
        .filter(value -> !value.isBlank())
        .map(String::trim)
        .orElse(null);
  }
}
