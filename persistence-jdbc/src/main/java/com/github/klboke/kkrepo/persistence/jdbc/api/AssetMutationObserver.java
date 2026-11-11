package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;

/** Optional protocol projections, invoked inside the asset transaction. No blob I/O is allowed. */
public interface AssetMutationObserver {
  /** Cheap in-memory filter; unrelated formats must not add projection reads. */
  boolean supports(AssetRecord asset);
  /** Acquire protocol identity locks before the asset row is changed. */
  void beforeMutation(AssetRecord asset);
  void stored(AssetRecord asset);
  void deleted(AssetRecord asset);
}
