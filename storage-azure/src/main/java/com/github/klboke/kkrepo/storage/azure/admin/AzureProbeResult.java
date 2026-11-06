package com.github.klboke.kkrepo.storage.azure.admin;

/** Outcome of an administrative read/write probe against an Azure blob store. */
public record AzureProbeResult(
    boolean ok,
    String objectKey,
    String message,
    AzureContainerSummary summary) {
}
