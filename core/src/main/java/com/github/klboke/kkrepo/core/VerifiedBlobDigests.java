package com.github.klboke.kkrepo.core;

/** Server-computed content digests, never the multipart provider's ETag. */
public record VerifiedBlobDigests(String sha256, String sha1, String md5, long size) {}
