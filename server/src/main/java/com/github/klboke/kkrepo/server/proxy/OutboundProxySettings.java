package com.github.klboke.kkrepo.server.proxy;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.OutboundProxySettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.OutboundProxySettingsDao.Settings;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import java.util.Map;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutboundProxySettings {
  private final OutboundProxySettingsDao dao;
  private final RepositoryDao repositories;
  private final ProxyStateDao proxyState;

  public OutboundProxySettings(OutboundProxySettingsDao dao, RepositoryDao repositories,
                               ProxyStateDao proxyState) {
    this.dao = dao;
    this.repositories = repositories;
    this.proxyState = proxyState;
  }

  public View read() {
    return view(dao.read());
  }

  @Transactional
  public View save(Command command) {
    Settings candidate = candidate(command);
    if (!dao.replace(candidate)) {
      throw conflict();
    }
    for (var repository : repositories.list()) {
      if (repository.type() == RepositoryType.PROXY && repository.attributes() != null
          && repository.attributes().get("proxy") instanceof Map<?, ?> proxy
          && OutboundProxyConfig.mode(proxy) == OutboundProxyConfig.Mode.INHERIT) {
        proxyState.clearBackoff(repository.id());
      }
    }
    return new View(candidate.revision() + 1, candidate.enabled(), candidate.type(), candidate.host(),
        candidate.port(), candidate.username(), candidate.password() != null, candidate.bypassHosts());
  }

  public OutboundProxyConfig runtimeProxy() {
    return runtimeProxy(dao.read());
  }

  public OutboundProxyConfig testProxy(Command command) {
    return runtimeProxy(candidate(command));
  }

  private Settings candidate(Command command) {
    if (command == null || command.revision() == null) {
      throw invalid("Settings revision is required");
    }
    Settings current = dao.read();
    if (current.revision() != command.revision()) {
      throw conflict();
    }
    String host = clean(command.host());
    var type = OutboundProxyConfig.parseType(command.type());
    Integer port = command.port();
    String username = clean(command.username());
    String password = command.password() != null && !command.password().isEmpty()
        ? command.password() : command.clearPassword() ? null : current.password();
    if (command.enabled() || host != null || port != null) {
      if (host == null || !validHost(host) || port == null || port < 1 || port > 65535 || type == null) {
        throw invalid("Specify HTTP or SOCKS5, a valid proxy host, and a port between 1 and 65535");
      }
    }
    if (username != null && username.length() > 255 || password != null && username == null) {
      throw invalid("Specify a proxy username when setting a password");
    }
    if (command.bypassHosts() != null && command.bypassHosts().size() > 100) {
      throw invalid("At most 100 bypass hosts are allowed");
    }
    Set<String> bypassHosts = new LinkedHashSet<>();
    for (String entry : command.bypassHosts() == null ? List.<String>of() : command.bypassHosts()) {
      if (entry == null) {
        throw invalid("Specify exact bypass hosts or subdomain patterns such as *.example.com");
      }
      String normalized = OutboundProxyConfig.normalizeHost(entry.trim());
      String domain = normalized.startsWith("*.") ? normalized.substring(2) : normalized;
      if (!validHost(domain) || (normalized.startsWith("*.")
          && (!domain.contains(".") || domain.matches("[0-9.]+")))) {
        throw invalid("Specify exact bypass hosts or subdomain patterns such as *.example.com");
      }
      bypassHosts.add(normalized);
    }
    return new Settings(current.revision(), command.enabled(), type == null ? null : type.name(), host,
        port, username, password, List.copyOf(bypassHosts));
  }

  public static boolean validHost(String host) {
    if (host.length() > 255) return false;
    if (host.contains(":")) {
      try {
        return InetAddress.ofLiteral(host).getAddress().length == 16;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
    return OutboundProxyConfig.normalizeHost(host)
        .matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*");
  }

  private static OutboundProxyConfig runtimeProxy(Settings settings) {
    return new OutboundProxyConfig(OutboundProxyConfig.parseType(settings.type()), settings.host(),
        settings.enabled() && settings.port() != null ? settings.port() : 0, settings.username(),
        settings.password(), Set.copyOf(settings.bypassHosts()), settings.revision());
  }

  private static View view(Settings settings) {
    return new View(settings.revision(), settings.enabled(), settings.type(), settings.host(),
        settings.port(), settings.username(), settings.password() != null, settings.bypassHosts());
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Proxy settings changed. Reload and try again.");
  }

  public record Command(Long revision, boolean enabled, String type, String host, Integer port,
                        String username, String password, boolean clearPassword, List<String> bypassHosts) {}

  public record View(long revision, boolean enabled, String type, String host, Integer port,
                     String username, boolean passwordConfigured, List<String> bypassHosts) {}
}
