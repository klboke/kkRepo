package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CargoSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.DockerSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.RawSettings;

public record RepositoryView(
    Long id,
    String name,
    String recipe,
    RepositoryFormat format,
    RepositoryType type,
    boolean online,
    String blobStoreName,
    boolean strictContentTypeValidation,
    String url,
    HostedSettings hosted,
    ProxySettings proxy,
    RawSettings raw,
    DockerSettings docker,
    CargoSettings cargo,
    GroupSettings group) {
}
