package com.example.kkrepo.test;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import java.util.List;

/** Built-in plugin whose descriptor points at another class. */
public final class TestMismatchedBuiltinPlugin implements KkRepoPlugin {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("test-mismatched", "1.0.0", PluginApiVersion.CURRENT,
        "com.example.OtherPlugin", List.of());
  }

  @Override
  public void contribute(PluginContext context) {
    throw new AssertionError("A mismatched built-in must not contribute");
  }
}
