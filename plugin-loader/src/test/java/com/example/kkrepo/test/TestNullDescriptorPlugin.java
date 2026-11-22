package com.example.kkrepo.test;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;

/** Built-in plugin with no descriptor, used to cover loader validation. */
public final class TestNullDescriptorPlugin implements KkRepoPlugin {

  @Override
  public PluginDescriptor descriptor() {
    return null;
  }

  @Override
  public void contribute(PluginContext context) {
    throw new AssertionError("A plugin without a descriptor must not contribute");
  }
}
