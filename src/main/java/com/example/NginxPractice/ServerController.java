package com.example.NginxPractice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ServerController {

	private static final Logger log = LoggerFactory.getLogger(ServerController.class);

	@Value("${server.port}")
	private String port;

	@GetMapping("/api/server1")
	public Map<String, String> server1() {
		log.info("Called /api/server1 on instance port {}", port);
		return Map.of(
				"endpoint", "server1",
				"instancePort", port,
				"message", "Response from Server 1"
		);
	}

	@GetMapping("/api/server2")
	public Map<String, String> server2() {
		log.info("Called /api/server2 on instance port {}", port);
		return Map.of(
				"endpoint", "server2",
				"instancePort", port,
				"message", "Response from Server 2"
		);
	}

	@GetMapping("/api/server3")
	public Map<String, String> server3() {
		log.info("Called /api/server3 on instance port {}", port);
		return Map.of(
				"endpoint", "server3",
				"instancePort", port,
				"message", "Response from Server 3"
		);
	}
}
