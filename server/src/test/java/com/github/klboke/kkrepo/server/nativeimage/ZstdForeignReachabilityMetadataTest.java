package com.github.klboke.kkrepo.server.nativeimage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.Zstd;
import java.io.InputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ZstdForeignReachabilityMetadataTest {
  private static final String METADATA_RESOURCE =
      "META-INF/native-image/com.github.klboke/kkrepo-server-runtime/reachability-metadata.json";

  @Test
  void registersEveryZstdFfmDowncallCreatedDuringBindingInitialization() throws Exception {
    Linker nativeLinker = Linker.nativeLinker();
    Set<Downcall> registered = new LinkedHashSet<>();
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(METADATA_RESOURCE)) {
      assertNotNull(input, () -> "Missing native-image metadata resource " + METADATA_RESOURCE);
      for (JsonNode call : new ObjectMapper().readTree(input).path("foreign").path("downcalls")) {
        MemoryLayout[] parameters = StreamSupport.stream(call.path("parameterTypes").spliterator(), false)
            .map(type -> layout(type.asText(), nativeLinker)).toArray(MemoryLayout[]::new);
        FunctionDescriptor descriptor = call.path("returnType").asText().equals("void")
            ? FunctionDescriptor.ofVoid(parameters)
            : FunctionDescriptor.of(layout(call.path("returnType").asText(), nativeLinker), parameters);
        List<Linker.Option> options = call.path("options").path("critical").path("allowHeapAccess").asBoolean()
            ? List.of(Linker.Option.critical(true)) : List.of();
        registered.add(new Downcall(descriptor, options));
      }
    }

    // Observe the installed dependency, rather than comparing metadata against another fixed list.
    // An isolated loader guarantees initialization even if other tests already used Zstd.
    Set<Downcall> observed = new LinkedHashSet<>();
    Linker recordingLinker = spy(nativeLinker);
    doAnswer(invocation -> {
      FunctionDescriptor descriptor = invocation.getArgument(1);
      Linker.Option[] options = (Linker.Option[]) invocation.getRawArguments()[2];
      MemoryLayout[] parameters = descriptor.argumentLayouts().stream()
          .map(ZstdForeignReachabilityMetadataTest::withoutNames).toArray(MemoryLayout[]::new);
      FunctionDescriptor normalized = descriptor.returnLayout()
          .map(result -> FunctionDescriptor.of(withoutNames(result), parameters))
          .orElseGet(() -> FunctionDescriptor.ofVoid(parameters));
      observed.add(new Downcall(normalized, List.of(options)));
      return nativeLinker.downcallHandle(invocation.getArgument(0), descriptor, options);
    }).when(recordingLinker).downcallHandle(any(MemorySegment.class), any(FunctionDescriptor.class),
        any(Linker.Option[].class));
    URL dependency = Zstd.class.getProtectionDomain().getCodeSource().getLocation();
    try (var loader = new URLClassLoader(new URL[] {dependency}, ClassLoader.getPlatformClassLoader());
         var linkerFactory = mockStatic(Linker.class)) {
      linkerFactory.when(Linker::nativeLinker).thenReturn(recordingLinker);
      Class.forName("com.github.luben.zstd.ZstdBinding", true, loader);
    }
    assertFalse(observed.isEmpty(), "Zstd FFM binding initialization must be exercised");
    Set<Downcall> missing = new LinkedHashSet<>(observed);
    missing.removeAll(registered);
    assertTrue(missing.isEmpty(), () -> "Unregistered Zstd FFM downcalls: " + missing);
  }

  private static MemoryLayout layout(String type, Linker linker) {
    if (type.equals("void*")) return ValueLayout.ADDRESS;
    if (type.startsWith("struct(")) {
      return MemoryLayout.structLayout(members(type.substring(7, type.length() - 1)).stream()
          .map(member -> layout(member, linker)).toArray(MemoryLayout[]::new));
    }
    MemoryLayout layout = linker.canonicalLayouts().get(type);
    assertNotNull(layout, () -> "Unsupported foreign metadata type " + type);
    return layout.withoutName();
  }

  private static List<String> members(String body) {
    List<String> members = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '(') depth++;
      if (c == ')') depth--;
      if (c == ',' && depth == 0) {
        members.add(body.substring(start, i));
        start = i + 1;
      }
    }
    members.add(body.substring(start));
    return members;
  }

  private static MemoryLayout withoutNames(MemoryLayout layout) {
    if (layout instanceof GroupLayout group) {
      return MemoryLayout.structLayout(group.memberLayouts().stream()
          .map(ZstdForeignReachabilityMetadataTest::withoutNames).toArray(MemoryLayout[]::new));
    }
    return layout.withoutName();
  }

  private record Downcall(FunctionDescriptor descriptor, List<Linker.Option> options) {}
}
