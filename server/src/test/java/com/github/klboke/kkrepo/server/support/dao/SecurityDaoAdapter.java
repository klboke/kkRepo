package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Test-only base class for focused SecurityDao fakes. */
public class SecurityDaoAdapter implements SecurityDao {
  public SecurityDaoAdapter() {
  }

  public SecurityDaoAdapter(Object ignored) {
  }

  public SecurityDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public void assignRole(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteApiKey(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteApiKeysForOwner(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deletePrivilege(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteRepositoryTarget(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteRole(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteUser(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<SecurityAnonymousConfigRecord> findAnonymousConfig() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ApiKeyRecord> findApiKey(String arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ApiKeyRecord> findApiKey(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ApiKeyRecord> findApiKeyByDomainAndHash(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ApiKeyRecord> findApiKeyByHash(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ApiKeyRecord> findApiKeyForOwner(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<SecurityPrivilegeRecord> findPrivilege(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<SecurityRealmRecord> findRealm(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<SecurityRepositoryTargetRecord> findRepositoryTarget(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<SecurityRoleRecord> findRole(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<SecurityUserRecord> findUser(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void grantPrivilege(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void inheritRole(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertPrivilegeIfAbsent(SecurityPrivilegeRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long insertUser(SecurityUserRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ApiKeyRecord> listApiKeys() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ApiKeyRecord> listApiKeysForOwner(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SecurityPrivilegeRecord> listPrivileges() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SecurityPrivilegeRecord> listPrivilegesForRoles(List<String> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SecurityRealmRecord> listRealms() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SecurityRepositoryTargetRecord> listRepositoryTargets() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listRoleChildIds(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listRolePrivilegeIds(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SecurityRoleRecord> listRoles() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listUserRoleIds(String arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listUserRoleIds(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SecurityUserRecord> listUsers() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markApiKeyUsed(long arg0, LocalDateTime arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removePrivilegeReferences(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeRoleReferences(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceRoleInheritance(String arg0, List<String> arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceRolePrivileges(String arg0, List<String> arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceUserRoles(long arg0, List<String> arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updatePasswordHash(String arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateRealmConfig(List<String> arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateUser(SecurityUserRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertAnonymousConfig(SecurityAnonymousConfigRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertApiKey(ApiKeyRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertPrivilege(SecurityPrivilegeRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertRealm(SecurityRealmRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertRepositoryTarget(SecurityRepositoryTargetRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertRole(SecurityRoleRecord arg0) {
    throw new UnsupportedOperationException();
  }
}
