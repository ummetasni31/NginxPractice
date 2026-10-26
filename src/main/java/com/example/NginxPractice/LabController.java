package com.example.NginxPractice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class LabController {

	@Value("${server.port}")
	private String port;

	@GetMapping(value = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events() {
		SseEmitter emitter = new SseEmitter(15_000L);
		AtomicBoolean finished = new AtomicBoolean();
		emitter.onCompletion(() -> finished.set(true));
		emitter.onTimeout(() -> finished.set(true));
		emitter.onError(error -> finished.set(true));
		Thread.startVirtualThread(() -> {
			try {
				for (int i = 1; i <= 5 && !finished.get(); i++) {
					emitter.send(SseEmitter.event().name("tick").id(String.valueOf(i))
							.data("Event " + i + " from port " + port));
					if (i < 5) Thread.sleep(1_000);
				}
				emitter.complete();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				emitter.completeWithError(error);
			} catch (IOException | IllegalStateException error) {
				emitter.completeWithError(error);
			}
		});
		return emitter;
	}

	@PostMapping(value = "/api/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty file");
		}
		// Only report metadata; never use a client filename as a server filesystem path.
		return Map.of("filename", file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
				"size", file.getSize(), "instancePort", port, "stored", false);
	}
}
