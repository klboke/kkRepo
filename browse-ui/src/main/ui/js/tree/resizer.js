// Drag (or arrow-key) the handle between tree and detail panes. The width is a per-browser
// preference only, so localStorage failures are ignored and the default 60% is used.
// The preferred percentage is kept separately from the effective (clamped) one so that the
// panes honour their minimum width again after the container shrinks or grows back.
const SPLIT_WIDTH_KEY = "kkrepo.browse.treePaneWidthPct";

const SPLIT_DEFAULT_PCT = 60;

const SPLIT_MIN_PANE_PX = 220;

function clampSplitPct(pct, totalPx) {
  const total = totalPx > 0 ? totalPx : 1;
  const min = Math.min(45, (SPLIT_MIN_PANE_PX / total) * 100);
  return Math.max(min, Math.min(100 - min, pct));
}

function initSplitResizer(split) {
  const handle = split?.querySelector(".split-handle");
  const tree = split?.querySelector(".tree-left");
  if (!handle || !tree) return;

  const splitWidth = () => split.getBoundingClientRect().width;
  const currentPct = () => (tree.getBoundingClientRect().width / (splitWidth() || 1)) * 100;

  let preferredPct = SPLIT_DEFAULT_PCT;
  try {
    const stored = parseFloat(localStorage.getItem(SPLIT_WIDTH_KEY));
    if (Number.isFinite(stored)) preferredPct = stored;
  } catch { /* ignore */ }

  // Renders the effective width for the current container size without touching the preference.
  const render = () => {
    const value = clampSplitPct(preferredPct, splitWidth());
    split.style.setProperty("--tree-pane-width", `${value}%`);
    handle.setAttribute("aria-valuenow", String(Math.round(value)));
    return value;
  };
  // Records a user-chosen width as the new preference, optionally persisting it.
  const setPreferred = (pct, persist) => {
    preferredPct = clampSplitPct(pct, splitWidth());
    render();
    if (persist) {
      try { localStorage.setItem(SPLIT_WIDTH_KEY, String(preferredPct)); } catch { /* ignore */ }
    }
  };
  render();

  // The tree is rebuilt on every repository render, so stop observing once this split is detached.
  if (typeof ResizeObserver === "function") {
    const observer = new ResizeObserver(() => {
      if (!split.isConnected) {
        observer.disconnect();
        return;
      }
      render();
    });
    observer.observe(split);
  }

  handle.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    split.classList.add("is-resizing");
    const onMove = (e) => {
      const rect = split.getBoundingClientRect();
      setPreferred(((e.clientX - rect.left) / rect.width) * 100, false);
    };
    const onEnd = (e) => {
      handle.removeEventListener("pointermove", onMove);
      handle.removeEventListener("pointerup", onEnd);
      handle.removeEventListener("pointercancel", onEnd);
      try { handle.releasePointerCapture(e.pointerId); } catch { /* ignore */ }
      split.classList.remove("is-resizing");
      setPreferred(preferredPct, true);
    };
    handle.addEventListener("pointermove", onMove);
    handle.addEventListener("pointerup", onEnd);
    handle.addEventListener("pointercancel", onEnd);
  });

  handle.addEventListener("keydown", (event) => {
    if (event.key === "ArrowLeft") setPreferred(currentPct() - 2, true);
    else if (event.key === "ArrowRight") setPreferred(currentPct() + 2, true);
    else if (event.key === "Home" || event.key === "Enter") setPreferred(SPLIT_DEFAULT_PCT, true);
    else return;
    event.preventDefault();
  });
  handle.addEventListener("dblclick", () => setPreferred(SPLIT_DEFAULT_PCT, true));
}

export {
  initSplitResizer,
};
