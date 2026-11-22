package com.example.kkrepo.test;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import java.util.List;

/** Built-in plugin reusing another built-in id to cover duplicate-id rejection. */
public final class TestDuplicateBuiltinPlugin implements KkRepoPlugin {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("test-builtin", "1.0.0", PluginApiVersion.CURRENT,
        getClass().getName(), List.of());
  }

  @Override
  public void contribute(PluginContext context) {
  }
}
