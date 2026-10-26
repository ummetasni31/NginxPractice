package com.example.NginxPractice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LabControllerTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void uploadReportsSizeWithoutStoringFile() throws Exception {
		mvc.perform(multipart("/api/upload").file(new MockMultipartFile("file", "demo.txt", "text/plain", new byte[]{1, 2, 3})))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(3))
				.andExpect(jsonPath("$.stored").value(false));
	}

	@Test
	void uploadRejectsEmptyAndMissingFiles() throws Exception {
		mvc.perform(multipart("/api/upload").file(new MockMultipartFile("file", new byte[0])))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/upload")).andExpect(status().isBadRequest());
	}

	@Test
	void eventStreamCompletesAfterFiveEvents() throws Exception {
		var request = mvc.perform(get("/api/events"))
				.andExpect(request().asyncStarted()).andReturn();
		request.getAsyncResult(10_000);
		mvc.perform(asyncDispatch(request))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/event-stream"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("id:5")))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id:6"))));
	}
}
