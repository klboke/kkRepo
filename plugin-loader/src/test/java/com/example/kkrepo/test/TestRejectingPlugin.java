package com.example.kkrepo.test;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import java.util.List;

/** Signed test plugin whose registration is rejected by the context contract. */
public final class TestRejectingPlugin implements KkRepoPlugin {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("test-rejecting", "1.0.0", PluginApiVersion.CURRENT,
        getClass().getName(), List.of());
  }

  @Override
  public void contribute(PluginContext context) {
    throw new IllegalArgumentException("rejected contribution");
  }
}
