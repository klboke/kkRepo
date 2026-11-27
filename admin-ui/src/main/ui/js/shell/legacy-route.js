if (window.location.hash === "#browse" || window.location.hash.startsWith("#browse/")) {
  window.location.replace(`/browse/${window.location.hash}`);
}
