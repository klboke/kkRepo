export const AUTH_SNAPSHOT_KEY = "nexusPlus.authSnapshot";
const MAX_AGE_MS = 10 * 60 * 1000;

export function readSessionSnapshot() {
  try {
    const raw = sessionStorage.getItem(AUTH_SNAPSHOT_KEY);
    if (!raw) return null;
    const snapshot = JSON.parse(raw);
    const age = Date.now() - Number(snapshot?.savedAt);
    if (snapshot?.session?.userId && age >= 0 && age <= MAX_AGE_MS) return snapshot;
  } catch {
    // Fall back to session discovery if storage is disabled or the snapshot is malformed.
  }
  clearSessionSnapshot();
  return null;
}

export function clearSessionSnapshot() {
  try {
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
  } catch {
    // Browsers may disable session storage.
  }
}

export function saveSessionSnapshot(session, permissions) {
  if (!session?.userId) return clearSessionSnapshot();
  try {
    sessionStorage.setItem(AUTH_SNAPSHOT_KEY, JSON.stringify({ session, permissions, savedAt: Date.now() }));
  } catch {
    // The service session is authoritative even if the browser cannot cache the snapshot.
  }
}
