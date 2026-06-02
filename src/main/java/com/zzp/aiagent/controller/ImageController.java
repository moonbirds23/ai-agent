package com.zzp.aiagent.controller;

import com.zzp.aiagent.service.ImageDownloadService;
import com.zzp.aiagent.model.dto.image.DownloadedImage;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/image")
@RequiredArgsConstructor
public class ImageController {

    private final ImageDownloadService imageDownloadService;

    @GetMapping("/download")
    public void download(@RequestParam String url, HttpServletResponse response) throws IOException {
        DownloadedImage image = imageDownloadService.download(url);
        response.setContentType(image.contentType());
        response.setContentLength(image.bytes().length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(image.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString());
        response.getOutputStream().write(image.bytes());
    }
}
