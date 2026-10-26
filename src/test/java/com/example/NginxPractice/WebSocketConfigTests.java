package com.example.NginxPractice;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebSocketConfigTests {

	@Test
	void registeredHandlerEchoesMessageWithInstancePort() throws Exception {
		WebSocketConfig config = new WebSocketConfig();
		ReflectionTestUtils.setField(config, "port", "8082");
		WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
		config.registerWebSocketHandlers(registry);
		ArgumentCaptor<WebSocketHandler> handler = ArgumentCaptor.forClass(WebSocketHandler.class);
		verify(registry).addHandler(handler.capture(), eq("/ws/echo"));
		WebSocketSession session = mock(WebSocketSession.class);
		handler.getValue().handleMessage(session, new TextMessage("Hello"));
		verify(session).sendMessage(new TextMessage("Port 8082 echoed: Hello"));
	}
}
