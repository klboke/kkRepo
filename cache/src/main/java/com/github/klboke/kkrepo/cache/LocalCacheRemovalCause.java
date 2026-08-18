package com.github.klboke.kkrepo.cache;

/** Backend-neutral reason why a node-local cache entry was removed. */
public enum LocalCacheRemovalCause {
  EXPLICIT,
  REPLACED,
  COLLECTED,
  EXPIRED,
  SIZE
}
