package com.github.klboke.kkrepo.storage.azure.admin;

/** Reachability snapshot of the container backing an Azure blob store. */
public record AzureContainerSummary(
    String name,
    String endpoint,
    String accountName,
    String container,
    String state,
    boolean containerExists,
    long objectCount,
    long totalSize,
    String message) {
}
