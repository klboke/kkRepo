package com.github.klboke.kkrepo.server.proxy;

import com.github.klboke.kkrepo.server.proxy.OutboundProxySettings.Command;
import com.github.klboke.kkrepo.server.proxy.OutboundProxySettings.View;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/proxy-settings")
public class OutboundProxySettingsController {
  private final OutboundProxySettings settings;

  public OutboundProxySettingsController(OutboundProxySettings settings) {
    this.settings = settings;
  }

  @GetMapping
  public View read() {
    return settings.read();
  }

  @PutMapping
  public View save(@RequestBody Command command) {
    return settings.save(command);
  }
}
