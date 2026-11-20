package com.github.klboke.kkrepo.server.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.gitlfs.GitLfsProtocolPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class PluginRuntimeHintsTest {

  @Test
  void registersEveryBuiltInPluginProviderAndItsServiceDescriptor() {
    RuntimeHints hints = new RuntimeHints();
    new PluginRuntimeHints().registerHints(hints, getClass().getClassLoader());

    assertTrue(RuntimeHintsPredicates.reflection().onType(GitLfsProtocolPlugin.class)
        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS).test(hints));
    assertTrue(RuntimeHintsPredicates.resource()
        .forResource("META-INF/services/" + KkRepoPlugin.class.getName()).test(hints));
    assertTrue(RuntimeHintsPredicates.reflection().onType(PluginDescriptor.class).test(hints));
  }
}
