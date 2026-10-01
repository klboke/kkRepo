-- Old basename-only indexes can conflate scoped tarballs with different release ages.
-- Rebuild them lazily from the shared raw packument; rolling older replicas still write version 1.
ALTER TABLE npm_release_index_revision ADD COLUMN identity_version INTEGER NOT NULL DEFAULT 1;
