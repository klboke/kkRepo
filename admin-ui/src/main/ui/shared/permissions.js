function permissionPartMatches(grantedPart, requestedPart, action = false) {
  const normalize = (part) => {
    const value = (part || "*").trim().toLowerCase();
    if (!action) return value;
    if (value === "create") return "add";
    if (value === "update") return "edit";
    return value === "all" ? "*" : value;
  };
  const requested = normalize(requestedPart);
  return (grantedPart || "*").split(",").some((part) => {
    // Backend ALL grants concrete actions, but only a literal * satisfies a wildcard request.
    if (action && (requestedPart || "*").trim() === "*") return part.trim() === "*";
    const normalized = normalize(part);
    return normalized === "*" || normalized === requested;
  });
}

function permissionMatches(granted, requested) {
  const grantedParts = (granted || "").toLowerCase().split(":");
  const requestedParts = (requested || "").toLowerCase().split(":");
  const domain = requestedParts[1];
  const actionIndex = ["repository-view", "repository-admin"].includes(domain) ? 4
    : domain === "repository-content-selector" ? 5
    : grantedParts[0] === "nexus" && requestedParts[0] === "nexus" ? 2 : -1;
  const length = Math.max(grantedParts.length, requestedParts.length);
  for (let index = 0; index < length; index += 1) {
    if (!permissionPartMatches(grantedParts[index], requestedParts[index], index === actionIndex)) return false;
  }
  return true;
}

export {
  permissionPartMatches,
  permissionMatches,
};
