package com.github.klboke.kkrepo.server.offload;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/download-offload")
public class DownloadOffloadController {
  private final DownloadOffloadSettings settings;

  public DownloadOffloadController(DownloadOffloadSettings settings) {
    this.settings = settings;
  }

  @GetMapping
  public DownloadOffloadSettings.View read() {
    return settings.read();
  }

  @PutMapping
  public DownloadOffloadSettings.View save(@RequestBody DownloadOffloadSettings.Command command) {
    return settings.save(command);
  }
}
