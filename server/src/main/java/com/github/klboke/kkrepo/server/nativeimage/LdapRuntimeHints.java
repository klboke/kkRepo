package com.github.klboke.kkrepo.server.nativeimage;

import java.util.List;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.util.ReflectionUtils;

/** JNDI instantiates the LDAP context factories by class name, so Native Image must keep them. */
public final class LdapRuntimeHints implements RuntimeHintsRegistrar {
  static final List<TypeReference> JNDI_FACTORIES = List.of(
      TypeReference.of("com.sun.jndi.ldap.LdapCtxFactory"),
      // Used when a referral or name is an ldap:// URL.
      TypeReference.of("com.sun.jndi.url.ldap.ldapURLContextFactory"));

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    JNDI_FACTORIES.forEach(type -> hints.reflection().registerType(
        type, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
    // ldaps:// makes com.sun.jndi.ldap.Connection call SSLSocketFactory.getDefault() reflectively.
    hints.reflection().registerMethod(
        ReflectionUtils.findMethod(SSLSocketFactory.class, "getDefault"), ExecutableMode.INVOKE);
  }
}
