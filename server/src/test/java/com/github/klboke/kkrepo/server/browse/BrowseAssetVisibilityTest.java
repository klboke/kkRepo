package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import org.junit.jupiter.api.Test;

class BrowseAssetVisibilityTest {

  @Test
  void ansibleHidesInternalAssetsWithoutHidingTheApiNamespace() {
    assertTrue(BrowseAssetVisibility.hidden(
        RepositoryFormat.ANSIBLEGALAXY, ".ansible/staging/task/archive.tar.gz"));
    assertTrue(BrowseAssetVisibility.hidden(
        RepositoryFormat.ANSIBLEGALAXY,
        AnsibleGalaxyPathParser.ARTIFACT_BASE + "acme-tools-1.2.3.tar.gz"));
    assertTrue(BrowseAssetVisibility.hidden(
        RepositoryFormat.ANSIBLEGALAXY,
        AnsibleGalaxyPathParser.ARTIFACT_BASE.substring(
            0, AnsibleGalaxyPathParser.ARTIFACT_BASE.length() - 1)));

    assertFalse(BrowseAssetVisibility.hidden(RepositoryFormat.ANSIBLEGALAXY, "api"));
    assertFalse(BrowseAssetVisibility.hidden(
        RepositoryFormat.ANSIBLEGALAXY,
        "api/tools/1.2.3/api-tools-1.2.3.tar.gz"));
  }
}
