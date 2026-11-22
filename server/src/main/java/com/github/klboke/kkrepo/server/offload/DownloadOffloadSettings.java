package com.github.klboke.kkrepo.server.offload;

import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.ABSOLUTE_PATH;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.INTERNAL_URI;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DownloadOffloadSettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DownloadOffloadSettingsDao.Settings;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DownloadOffloadSettings {
  static final String VERSION = "settings.download-offload";

  private final LocalCache<Long, RuntimeSettings> snapshots = LocalCacheFactory.standard()
      .<Long, RuntimeSettings>builder("download-offload-settings")
      .maximumSize(2)
      .expireAfterWrite(Duration.ofMinutes(1))
      .build();
  private final DownloadOffloadSettingsDao dao;
  private final BlobStoreDao stores;
  private final RepositoryDao repositories;
  private final VersionWatermark versions;
  private final ForwardedHeaderPolicy trustedPeers;
  private final String trustedProxyAddresses;

  public DownloadOffloadSettings(
      DownloadOffloadSettingsDao dao, BlobStoreDao stores, RepositoryDao repositories, VersionWatermark versions,
      @Value("${kkrepo.download-offload.trusted-proxies:}") String trustedProxyAddresses) {
    this.dao = dao;
    this.stores = stores;
    this.repositories = repositories;
    this.versions = versions;
    this.trustedProxyAddresses = trustedProxyAddresses;
    this.trustedPeers = new ForwardedHeaderPolicy(trustedProxyAddresses);
  }

  View read() {
    return view(dao.read(), availableStores());
  }

  @Transactional
  public View save(Command command) {
    if (command.revision() == null || command.enabled() == null) {
      throw invalid("Specify the settings revision and whether offload is enabled.");
    }
    if (DownloadOffloadMethod.byId(command.method()) == null) {
      throw invalid("Select a proxy preset.");
    }
    String customHeader = command.customHeader() == null ? "" : command.customHeader().trim();
    if (!DownloadOffloadMethod.validCustomHeader(customHeader)) {
      throw invalid("Enter a valid dedicated response header name.");
    }
    if (!INTERNAL_URI.equals(command.customTarget()) && !ABSOLUTE_PATH.equals(command.customTarget())) {
      throw invalid("Select a file location format.");
    }
    var available = availableStores();
    List<Long> selected = command.storeIds() == null ? List.of() : command.storeIds();
    if (selected.stream().anyMatch(Objects::isNull)) {
      throw invalid("Select valid File Blob Stores.");
    }
    selected = selected.stream().distinct().sorted().toList();
    Set<Long> availableIds = available.stream().map(Store::id).collect(java.util.stream.Collectors.toSet());
    if (!availableIds.containsAll(selected) || (command.enabled() && selected.isEmpty())) {
      throw invalid("Select at least one available File Blob Store to enable download offload.");
    }
    var candidate = new Settings(command.revision(), command.enabled(), command.method(),
        customHeader, command.customTarget(), selected);
    if (!dao.replace(candidate)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Download offload settings changed. Reload and try again.");
    }
    versions.bump(VERSION);
    return view(new Settings(candidate.revision() + 1, candidate.enabled(), candidate.method(),
        candidate.customHeader(), candidate.customTarget(), candidate.storeIds()), available);
  }

  boolean trusted(HttpServletRequest request) {
    return trustedPeers.trusted(request);
  }

  RuntimeSettings runtime() {
    try {
      return snapshots.get(versions.current(VERSION), version -> {
        Settings saved = dao.read();
        var method = DownloadOffloadMethod.byId(saved.method());
        return new RuntimeSettings(saved.enabled(), method.resolve(saved.customHeader(), saved.customTarget()),
            Set.copyOf(saved.storeIds()));
      });
    } catch (DataAccessException unavailable) {
      return null;
    }
  }

  private List<Store> availableStores() {
    var allRepositories = repositories.list();
    return stores.list().stream()
        .filter(record -> FileBlobStoreConfig.isFileStore(record.type(), record.attributes()))
        .sorted(Comparator.comparing(BlobStoreRecord::name))
        .map(record -> new Store(record.id(), record.name(), allRepositories.stream()
            .filter(repo -> Objects.equals(repo.blobStoreId(), record.id()))
            .map(RepositoryRecord::name).sorted().toList()))
        .toList();
  }

  private View view(Settings settings, List<Store> available) {
    var methods = DownloadOffloadMethod.METHODS.stream()
        .map(method -> new Method(method.id(), method.label(), method.header(), method.target())).toList();
    return new View(settings.revision(), settings.enabled(), settings.method(), settings.customHeader(),
        settings.customTarget(), settings.storeIds(), trustedProxyAddresses, available, methods);
  }

  private static ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  public record Command(Long revision, Boolean enabled, String method, String customHeader, String customTarget,
                        List<Long> storeIds) {}
  public record Store(long id, String name, List<String> repositories) {}
  public record Method(String id, String label, String responseHeader, String target) {}
  public record View(long revision, boolean enabled, String method, String customHeader, String customTarget,
                     List<Long> storeIds, String trustedProxies, List<Store> stores, List<Method> methods) {}
  record RuntimeSettings(boolean enabled, DownloadOffloadMethod.Contract contract, Set<Long> storeIds) {}
}
