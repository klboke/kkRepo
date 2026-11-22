package com.example.kkrepo.test;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import java.util.List;

/** Signed test plugin whose registration fails with a runtime error. */
public final class TestThrowingPlugin implements KkRepoPlugin {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("test-throwing", "1.0.0", PluginApiVersion.CURRENT,
        getClass().getName(), List.of());
  }

  @Override
  public void contribute(PluginContext context) {
    throw new IllegalStateException("runtime contribution failure");
  }
}
