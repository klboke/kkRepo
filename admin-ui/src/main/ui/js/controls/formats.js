import { formatIconName } from "#ui-shared/format-icons.js";
import { escapeHtml, lowerOrEmpty } from "./display.js";

const FORMAT_DISPLAY_NAMES = Object.freeze({
  maven2: "Maven",
  npm: "npm",
  pypi: "PyPI",
  cargo: "Cargo / Rust",
  pub: "Dart / Pub",
  composer: "Composer / PHP",
  go: "Go",
  helm: "Helm",
  docker: "Docker / OCI",
  nuget: "NuGet",
  rubygems: "RubyGems",
  yum: "Yum / RPM",
  terraform: "Terraform",
  swift: "Swift",
  ansiblegalaxy: "Ansible Galaxy",
  conda: "Conda",
  conan: "Conan 2",
  apt: "APT / Debian",
  alpine: "APK / Alpine",
  r: "R / CRAN",
  huggingface: "Hugging Face Models",
  gitlfs: "Git LFS",
  raw: "Raw",
});

function formatBadge(format) {
  const normalized = lowerOrEmpty(format);
  const icon = formatIconName(normalized);
  return `<span class="format-cell"><span class="format-logo format-logo-${icon}" aria-hidden="true"></span><span class="format-cell-label">${escapeHtml(normalized)}</span></span>`;
}

export {
  FORMAT_DISPLAY_NAMES,
  formatBadge,
  formatIconName,
};
