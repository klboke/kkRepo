package com.github.klboke.kkrepo.protocol.gitlfs;

import java.util.List;
import java.util.Objects;

/**
 * Host decision for each object of a Batch request, in request order. The protocol plugin renders it
 * as the Batch response.
 *
 * @param objectBaseUrl public URL of the repository, ending with {@code /}; object actions are
 *     {@code objectBaseUrl + oid}
 * @param objects one result per requested object
 */
public record GitLfsBatchResult(String objectBaseUrl, List<ObjectResult> objects) {

  public GitLfsBatchResult {
    Objects.requireNonNull(objectBaseUrl, "objectBaseUrl");
    objects = List.copyOf(objects);
  }

  /** What the client must do with one object. */
  public enum Outcome {
    /** The object is rejected; see the error code and message. */
    ERROR,
    /** Upload of an object that is already stored: no action. */
    PRESENT,
    /** Upload through the issued lease, then verify. */
    UPLOAD,
    /** Download from the repository. */
    DOWNLOAD
  }

  /**
   * @param uploadId lease id for {@link Outcome#UPLOAD}
   * @param expiresInSeconds lease lifetime for {@link Outcome#UPLOAD}
   * @param errorCode and {@code errorMessage} describe {@link Outcome#ERROR}
   */
  public record ObjectResult(String oid, long size, Outcome outcome, String uploadId,
      long expiresInSeconds, int errorCode, String errorMessage) {

    public ObjectResult {
      Objects.requireNonNull(outcome, "outcome");
    }

    public static ObjectResult error(String oid, long size, int code, String message) {
      return new ObjectResult(oid, size, Outcome.ERROR, null, 0, code, message);
    }

    public static ObjectResult present(String oid, long size) {
      return new ObjectResult(oid, size, Outcome.PRESENT, null, 0, 0, null);
    }

    public static ObjectResult upload(String oid, long size, String uploadId, long expiresInSeconds) {
      return new ObjectResult(oid, size, Outcome.UPLOAD, Objects.requireNonNull(uploadId, "uploadId"),
          expiresInSeconds, 0, null);
    }

    public static ObjectResult download(String oid, long size) {
      return new ObjectResult(oid, size, Outcome.DOWNLOAD, null, 0, 0, null);
    }

    public boolean isError() {
      return outcome == Outcome.ERROR;
    }
  }
}
