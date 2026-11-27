const declarations = require("../../../../scripts/ui/test-fixture.cjs")("browse");
const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");

const extractFunction = name => declarations(name);

function render(downloadUrl) {
  const context = declarations.context({
    lucideIcon: () => "<svg></svg>",
  });
  vm.runInContext([
    "escapeHtml",
    "renderAssetDownloadLink",
  ].map(extractFunction).join("\n"), context);
  return context.renderAssetDownloadLink(downloadUrl);
}

test("asset download links escape attribute-breaking path content", () => {
  const payload = "/repository/raw/archive.zip\" onmouseover=\"alert(1)\" data-x=\"";
  const html = render(payload);

  assert.match(html, /href="\/repository\/raw\/archive\.zip&quot; onmouseover=&quot;alert\(1\)&quot; data-x=&quot;"/);
  assert.doesNotMatch(html, /" onmouseover="/);
  assert.match(html, /target="_blank" rel="noopener noreferrer"/);
});

test("asset download links ignore non-string or empty targets", () => {
  assert.equal(render(""), "");
  assert.equal(render(null), "");
  assert.equal(render({ href: "/repository/raw/archive.zip" }), "");
});
