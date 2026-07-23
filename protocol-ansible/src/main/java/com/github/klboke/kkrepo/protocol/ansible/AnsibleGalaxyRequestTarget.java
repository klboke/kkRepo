package com.github.klboke.kkrepo.protocol.ansible;

public record AnsibleGalaxyRequestTarget(
    AnsibleGalaxyPath path,
    int limit,
    int offset) {
}
