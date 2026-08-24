package com.github.klboke.kkrepo.server;

import com.github.klboke.kkrepo.server.alpine.AlpineService;
import com.github.klboke.kkrepo.server.ansible.AnsibleGalaxyMultipartReader;
import com.github.klboke.kkrepo.server.ansible.AnsibleGalaxyService;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.conda.CondaService;
import com.github.klboke.kkrepo.server.conan.ConanService;
import com.github.klboke.kkrepo.server.huggingface.HuggingFaceService;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.r.RService;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import com.github.klboke.kkrepo.server.routing.builtin.BuiltinRepositoryProtocolHandler;
import com.github.klboke.kkrepo.server.swift.SwiftService;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/** Keeps focused legacy routing tests readable while production controllers stay protocol-free. */
public final class RepositoryProtocolControllerTestSupport extends RepositoryProtocolController {
  private final BuiltinRepositoryProtocolHandler handler;

  private RepositoryProtocolControllerTestSupport(
      RepositoryProtocolDispatcher dispatcher, BuiltinRepositoryProtocolHandler handler) {
    super(dispatcher);
    this.handler = handler;
  }

  public static RepositoryProtocolControllerTestSupport controller(
      RepositoryRuntimeRegistry runtimes, Object... dependencies) {
    BuiltinRepositoryProtocolHandler handler = instantiate(dependencies);
    return new RepositoryProtocolControllerTestSupport(
        new RepositoryProtocolDispatcher(runtimes, List.of(handler)), handler);
  }

  private static BuiltinRepositoryProtocolHandler instantiate(Object[] arguments) {
    Constructor<?> constructor = Arrays.stream(BuiltinRepositoryProtocolHandler.class
            .getConstructors())
        .filter(candidate -> candidate.getParameterCount() == arguments.length)
        .filter(candidate -> compatible(candidate.getParameterTypes(), arguments))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "No built-in protocol handler constructor accepts " + arguments.length
                + " test arguments"));
    try {
      return (BuiltinRepositoryProtocolHandler) constructor.newInstance(arguments);
    } catch (InstantiationException | IllegalAccessException error) {
      throw new IllegalStateException("Cannot create built-in protocol test handler", error);
    } catch (InvocationTargetException error) {
      throw new IllegalStateException(
          "Built-in protocol test handler construction failed", error.getCause());
    }
  }

  private static boolean compatible(Class<?>[] parameterTypes, Object[] arguments) {
    for (int index = 0; index < parameterTypes.length; index++) {
      if (arguments[index] != null && !parameterTypes[index].isInstance(arguments[index])) {
        return false;
      }
    }
    return true;
  }

  void setSwiftService(SwiftService service) {
    handler.setSwiftService(service);
  }

  void setAnsibleGalaxyService(AnsibleGalaxyService service) {
    handler.setAnsibleGalaxyService(service);
  }

  void setAnsibleGalaxyMultipartReader(AnsibleGalaxyMultipartReader reader) {
    handler.setAnsibleGalaxyMultipartReader(reader);
  }

  void setCondaService(CondaService service) {
    handler.setCondaService(service);
  }

  void setConanService(ConanService service) {
    handler.setConanService(service);
  }

  void setAptService(AptService service) {
    handler.setAptService(service);
  }

  void setAlpineService(AlpineService service) {
    handler.setAlpineService(service);
  }

  void setHuggingFaceService(HuggingFaceService service) {
    handler.setHuggingFaceService(service);
  }

  void setRService(RService service) {
    handler.setRService(service);
  }
}
