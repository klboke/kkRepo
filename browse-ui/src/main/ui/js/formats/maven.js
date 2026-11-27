import { parseGav, usageSnippet } from "../details/pane.js";
import { state } from "../tree/browser.js";

function mavenUsageDetail(entry) {
  const gav = parseGav(entry.path);
  if (!gav) return null;
  const { groupId, artifactId, version } = gav;
  const mavenSnippet =
`<dependency>
    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${version}</version>
</dependency>`;
  const gradleSnippet = `implementation '${groupId}:${artifactId}:${version}'`;
  const gradleKtsSnippet = `implementation("${groupId}:${artifactId}:${version}")`;
  const sbtSnippet = `libraryDependencies += "${groupId}" % "${artifactId}" % "${version}"`;
  const ivySnippet = `<dependency org="${groupId}" name="${artifactId}" rev="${version}" />`;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "maven2"],
      ["Group", groupId],
      ["Name", artifactId],
      ["Version", version],
      ["Most popular version", "", { locked: true }],
      ["Age", "", { locked: true }],
      ["Popularity", "", { locked: true }],
    ],
    snippets: [
      usageSnippet("Apache Maven", mavenSnippet, "Insert this snippet into your pom.xml"),
      usageSnippet("Gradle Groovy DSL", gradleSnippet, "Add to dependencies { } in build.gradle"),
      usageSnippet("Gradle Kotlin DSL", gradleKtsSnippet, "Add to dependencies { } in build.gradle.kts"),
      usageSnippet("Apache Ivy", ivySnippet, "Insert into ivy.xml <dependencies>"),
      usageSnippet("sbt", sbtSnippet, "Add to build.sbt"),
    ],
  };
}

export {
  mavenUsageDetail,
};
