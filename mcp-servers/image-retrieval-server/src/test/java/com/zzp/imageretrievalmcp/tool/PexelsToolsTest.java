package com.zzp.imageretrievalmcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.imageretrievalmcp.contract.PexelsPhotoDTO;
import com.zzp.imageretrievalmcp.contract.PexelsSearchResponse;
import com.zzp.imageretrievalmcp.observability.McpServerTelemetry;
import com.zzp.imageretrievalmcp.pexels.PexelsPhotoService;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PexelsTools} — verifies JSON output format
 * and delegation to {@link PexelsPhotoService}.
 */
@ExtendWith(MockitoExtension.class)
class PexelsToolsTest {

    @Mock
    private PexelsPhotoService pexelsPhotoService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpServerTelemetry telemetry = new McpServerTelemetry(
            new DefaultListableBeanFactory().getBeanProvider(OpenTelemetry.class));

    @Test
    void pexelsSearchPhotosShouldReturnValidJson() throws Exception {
        // Since @InjectMocks doesn't handle the ObjectMapper constructor arg,
        // recreate the tool with mock + real ObjectMapper
        PexelsTools tools = new PexelsTools(pexelsPhotoService, objectMapper, telemetry);

        PexelsPhotoDTO.PhotoSrc src = new PexelsPhotoDTO.PhotoSrc(
                "https://example.com/1.jpg", "", "", "", "", "", "", "");
        PexelsPhotoDTO photo = new PexelsPhotoDTO(
                1L, 1920, 1080, "test alt", "John Doe",
                "https://pexels.com/@john", "https://pexels.com/photo/1",
                "#FF0000", src);
        PexelsSearchResponse mockResponse = new PexelsSearchResponse(
                "1.0", "req-123", "pexels", "nature",
                150L, 10, 1, 5, List.of(photo));

        when(pexelsPhotoService.searchPhotos(any())).thenReturn(mockResponse);

        String result = tools.pexelsSearchPhotos("nature", 5, 1);

        assertNotNull(result);
        assertTrue(result.contains("\"query\":\"nature\""));
        assertTrue(result.contains("\"totalResults\":10"));
        assertTrue(result.contains("\"latencyMs\":150"));
        assertTrue(result.contains("\"photographer\":\"John Doe\""));
        assertTrue(result.contains("\"avg_color\":\"#FF0000\""));
        assertTrue(result.contains("\"id\":1"));
        // Verify it's valid JSON
        objectMapper.readTree(result);
    }

    @Test
    void pexelsCuratedPhotosShouldReturnValidJson() throws Exception {
        PexelsTools tools = new PexelsTools(pexelsPhotoService, objectMapper, telemetry);

        PexelsSearchResponse mockResponse = new PexelsSearchResponse(
                "1.0", "req-456", "pexels", null,
                100L, 50, 2, 5, List.of());

        when(pexelsPhotoService.curatedPhotos(5, 2)).thenReturn(mockResponse);

        String result = tools.pexelsCuratedPhotos(5, 2);

        assertNotNull(result);
        assertTrue(result.contains("\"totalResults\":50"));
        assertTrue(result.contains("\"page\":2"));
        // Verify it's valid JSON
        objectMapper.readTree(result);
    }

    @Test
    void pexelsGetPhotoShouldReturnValidJson() throws Exception {
        PexelsTools tools = new PexelsTools(pexelsPhotoService, objectMapper, telemetry);

        PexelsPhotoDTO.PhotoSrc src = new PexelsPhotoDTO.PhotoSrc(
                "https://example.com/999.jpg", "", "", "", "", "", "", "");
        PexelsPhotoDTO mockPhoto = new PexelsPhotoDTO(
                999L, 800, 600, "detail photo", "Jane Doe",
                "https://pexels.com/@jane", "https://pexels.com/photo/999",
                "#00FF00", src);

        when(pexelsPhotoService.getPhoto(999)).thenReturn(mockPhoto);

        String result = tools.pexelsGetPhoto(999);

        assertNotNull(result);
        assertTrue(result.contains("\"id\":999"));
        assertTrue(result.contains("\"width\":800"));
        assertTrue(result.contains("\"height\":600"));
        assertTrue(result.contains("\"photographer\":\"Jane Doe\""));
        // Verify it's valid JSON
        objectMapper.readTree(result);
    }

    @Test
    void pexelsSearchPhotosShouldReturnErrorJsonOnException() throws Exception {
        PexelsTools tools = new PexelsTools(pexelsPhotoService, objectMapper, telemetry);

        when(pexelsPhotoService.searchPhotos(any()))
                .thenThrow(new RuntimeException("Network timeout"));

        String result = tools.pexelsSearchPhotos("test", 5, 1);

        assertNotNull(result);
        assertTrue(result.contains("\"error\":\"Network timeout\""));
        // Verify it's still valid JSON even on error
        objectMapper.readTree(result);
    }

    @Test
    void pexelsGetPhotoShouldReturnErrorJsonOnException() throws Exception {
        PexelsTools tools = new PexelsTools(pexelsPhotoService, objectMapper, telemetry);

        when(pexelsPhotoService.getPhoto(404))
                .thenThrow(new RuntimeException("Photo not found"));

        String result = tools.pexelsGetPhoto(404);

        assertNotNull(result);
        assertTrue(result.contains("\"error\":\"Photo not found\""));
        objectMapper.readTree(result);
    }

    @Test
    void pexelsSearchPhotosShouldEscapeSpecialCharsInErrorMessage() throws Exception {
        PexelsTools tools = new PexelsTools(pexelsPhotoService, objectMapper, telemetry);

        when(pexelsPhotoService.searchPhotos(any()))
                .thenThrow(new RuntimeException("Error with \"quotes\" and \\backslash"));

        String result = tools.pexelsSearchPhotos("test", 5, 1);

        assertNotNull(result);
        assertTrue(result.contains("quotes"));
        assertTrue(result.contains("backslash"));
        // Verify it's still valid JSON
        objectMapper.readTree(result);
    }
}
