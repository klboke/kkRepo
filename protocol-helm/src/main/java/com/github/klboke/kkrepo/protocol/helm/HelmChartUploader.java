package com.github.klboke.kkrepo.protocol.helm;

import com.github.klboke.kkrepo.plugin.api.MultipartPart;
import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import java.io.IOException;

/**
 * Host-side Helm chart upload operation. The plugin only owns the HTTP route and multipart
 * validation; chart validation, asset writes, index updates and audit stay in the host service and
 * its transaction boundary.
 */
public interface HelmChartUploader extends PluginHostService {

  int upload(
      RepositoryDescriptor repository,
      MultipartPart chart,
      String createdBy,
      String createdByIp) throws IOException;
}
