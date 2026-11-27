const FORMAT_ICON_NAMES = Object.freeze({
  maven2: "maven",
  npm: "npm",
  ohpm: "ohpm",
  pypi: "pypi",
  cargo: "cargo",
  pub: "pub",
  composer: "composer",
  terraform: "terraform",
  swift: "swift",
  ansiblegalaxy: "ansiblegalaxy",
  conda: "conda",
  conan: "conan",
  apt: "apt",
  alpine: "alpine",
  r: "r",
  huggingface: "huggingface",
  gitlfs: "gitlfs",
  go: "go",
  helm: "helm",
  docker: "docker",
  nuget: "nuget",
  rubygems: "rubygems",
  yum: "yum",
  raw: "raw",
});

export function formatIconName(format) {
  return FORMAT_ICON_NAMES[String(format || "").toLowerCase()] || "raw";
}
