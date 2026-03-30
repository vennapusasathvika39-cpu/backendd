package com.frameforge.controller;

import com.frameforge.service.VideoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class VideoController {

    @Autowired
    private VideoService videoService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ok = videoService.isFFmpegAvailable();
        return ResponseEntity.ok(Map.of("status","UP","ffmpeg",ok,
            "message", ok ? "FFmpeg ready!" : "FFmpeg not found"));
    }

    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convert(
            @RequestParam("images")                                   List<MultipartFile> images,
            @RequestParam(value="imageDuration",  defaultValue="3")   double imageDuration,
            @RequestParam(value="resolution",     defaultValue="1280x720") String resolution,
            @RequestParam(value="format",         defaultValue="mp4") String format,
            @RequestParam(value="filter",         defaultValue="none") String filter,
            @RequestParam(value="transition",     defaultValue="none") String transition,
            @RequestParam(value="transitionDuration", defaultValue="0.8") double transitionDuration,
            @RequestParam(value="music",          required=false)     MultipartFile music
    ) {
        try {
            if (images == null || images.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error","No images provided."));
            if (images.size() > 15)
                return ResponseEntity.badRequest().body(Map.of("error","Max 15 images."));
            for (MultipartFile f : images) {
                String ct = f.getContentType();
                if (ct == null || !ct.startsWith("image/"))
                    return ResponseEntity.badRequest()
                           .body(Map.of("error","Only images accepted: " + f.getOriginalFilename()));
            }

            byte[] video = videoService.convert(images, imageDuration, resolution,
                    format, filter, transition, transitionDuration, music);

            MediaType mt = switch (format.toLowerCase()) {
                case "avi"  -> MediaType.parseMediaType("video/x-msvideo");
                case "webm" -> MediaType.parseMediaType("video/webm");
                default     -> MediaType.parseMediaType("video/mp4");
            };

            return ResponseEntity.ok()
                    .contentType(mt)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"frameforge_output." + format + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(video.length))
                    .body(video);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                   .body(Map.of("error", e.getMessage()));
        }
    }
}
