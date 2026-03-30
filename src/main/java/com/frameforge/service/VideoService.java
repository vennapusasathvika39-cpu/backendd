package com.frameforge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

@Service
public class VideoService {

    private static final Logger log = Logger.getLogger(VideoService.class.getName());

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    public boolean isFFmpegAvailable() {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    public byte[] convert(List<MultipartFile> images,
                          double imageDurationSec,   // seconds per image (NOT fps)
                          String resolution,
                          String format,
                          String filter,
                          String transition,
                          double transitionDuration,
                          MultipartFile musicFile) throws Exception {

        if (images == null || images.isEmpty())
            throw new IllegalArgumentException("No images provided.");
        if (images.size() > 15)
            throw new IllegalArgumentException("Maximum 15 images allowed.");

        Path workDir = Files.createTempDirectory("frameforge-");

        try {
            // Save images
            for (int i = 0; i < images.size(); i++) {
                Path dest = workDir.resolve(String.format("frame_%04d.jpg", i));
                Files.write(dest, images.get(i).getBytes());
            }

            // Save music if provided
            Path musicPath = null;
            if (musicFile != null && !musicFile.isEmpty()) {
                String musicExt = getExt(musicFile.getOriginalFilename(), "mp3");
                musicPath = workDir.resolve("music." + musicExt);
                Files.write(musicPath, musicFile.getBytes());
            }

            String[] parts = resolution.split("x");
            String w = parts[0], h = parts[1];

            // Clamp transition duration to at most 40% of image duration
            double maxTDur = imageDurationSec * 0.4;
            double tDur = Math.min(Math.max(transitionDuration, 0.2), Math.max(maxTDur, 0.2));

            Path outputPath = workDir.resolve("output." + format);

            List<String> cmd;
            if ("none".equalsIgnoreCase(transition) || images.size() == 1) {
                cmd = buildSimple(workDir, outputPath.toString(),
                        imageDurationSec, w, h, format, filter, images.size(), musicPath);
            } else {
                cmd = buildTransition(workDir, outputPath.toString(),
                        imageDurationSec, w, h, format, filter,
                        transition, tDur, images.size(), musicPath);
            }

            log.info("CMD: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(workDir.toFile());
            Process proc = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append("\n");
            }

            int exit = proc.waitFor();
            if (exit != 0)
                throw new RuntimeException("FFmpeg failed (exit " + exit + "):\n" + out);
            if (!Files.exists(outputPath))
                throw new RuntimeException("FFmpeg produced no output file.");

            return Files.readAllBytes(outputPath);

        } finally {
            deleteDir(workDir);
        }
    }

    // ── Simple (no transition) ────────────────────────────────────────────────
    private List<String> buildSimple(Path workDir, String output,
                                     double imgDur, String w, String h,
                                     String format, String filter,
                                     int count, Path music) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        double fps = 25.0; // fixed output fps for smooth playback
        cmd.add("-framerate"); cmd.add("1/" + String.format("%.3f", imgDur));
        cmd.add("-f"); cmd.add("image2");
        cmd.add("-i"); cmd.add(workDir.resolve("frame_%04d.jpg").toString());

        if (music != null) {
            cmd.add("-i"); cmd.add(music.toString());
        }

        String vf = buildVF(w, h, filter, imgDur, fps, false);
        cmd.add("-vf"); cmd.add(vf);
        cmd.add("-r"); cmd.add(String.valueOf((int)fps));

        addCodec(cmd, format, music != null, imgDur * count);
        cmd.add("-frames:v"); cmd.add(String.valueOf(count));
        cmd.add("-y"); cmd.add(output);
        return cmd;
    }

    // ── With transitions ──────────────────────────────────────────────────────
    private List<String> buildTransition(Path workDir, String output,
                                         double imgDur, String w, String h,
                                         String format, String filter,
                                         String transition, double tDur,
                                         int count, Path music) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        for (int i = 0; i < count; i++) {
            cmd.add("-loop"); cmd.add("1");
            cmd.add("-t");    cmd.add(String.format("%.3f", imgDur));
            cmd.add("-i");    cmd.add(workDir.resolve(String.format("frame_%04d.jpg", i)).toString());
        }

        if (music != null) {
            cmd.add("-i"); cmd.add(music.toString());
        }

        StringBuilder fg = new StringBuilder();

        // Scale + filter each input
        for (int i = 0; i < count; i++) {
            fg.append("[").append(i).append(":v]")
              .append(buildVF(w, h, filter, imgDur, 25, true))
              .append("[v").append(i).append("];");
        }

        if (count == 1) {
            fg.append("[v0]copy[outv]");
        } else {
            String prev = "v0";
            for (int i = 1; i < count; i++) {
                String outLabel = (i == count - 1) ? "outv" : "t" + i;
                double offset = imgDur + (i - 1) * (imgDur - tDur);
                if (offset < 0.01) offset = 0.01;

                fg.append("[").append(prev).append("][v").append(i).append("]")
                  .append("xfade=transition=").append(getXfade(transition))
                  .append(":duration=").append(String.format("%.3f", tDur))
                  .append(":offset=").append(String.format("%.3f", offset))
                  .append("[").append(outLabel).append("];");

                prev = outLabel;
            }
        }

        // Fade in/out
        if ("fade".equalsIgnoreCase(filter)) {
            double totalDur = imgDur * count - tDur * (count - 1);
            fg.append("[outv]fade=t=in:st=0:d=0.5,fade=t=out:st=")
              .append(String.format("%.3f", Math.max(0, totalDur - 0.5)))
              .append(":d=0.5[finalv]");
            cmd.add("-filter_complex"); cmd.add(fg.toString());
            cmd.add("-map"); cmd.add("[finalv]");
        } else {
            cmd.add("-filter_complex"); cmd.add(fg.toString());
            cmd.add("-map"); cmd.add("[outv]");
        }

        if (music != null) {
            cmd.add("-map"); cmd.add(count + ":a");
        }

        double totalDur = imgDur * count - tDur * (count - 1);
        addCodec(cmd, format, music != null, totalDur);
        cmd.add("-y"); cmd.add(output);
        return cmd;
    }

    // ── Video filter ──────────────────────────────────────────────────────────
    private String buildVF(String w, String h, String filter,
                           double imgDur, double fps, boolean forComplex) {
        String base = "scale=" + w + ":" + h
                + ":force_original_aspect_ratio=decrease"
                + ",pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:color=black"
                + ",format=yuv420p";

        String fx = switch (filter == null ? "none" : filter.toLowerCase()) {
            case "grayscale"  -> ",hue=s=0";
            case "sepia"      -> ",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131";
            case "vivid"      -> ",eq=saturation=1.8:contrast=1.1:brightness=0.05";
            case "cool"       -> ",colorbalance=bs=0.1:bh=0.1";
            case "warm"       -> ",colorbalance=rs=0.15:rh=0.1";
            case "vignette"   -> ",vignette=PI/4";
            case "blur"       -> ",gblur=sigma=2";
            case "sharpen"    -> ",unsharp=5:5:1.5:5:5:0";
            case "kenburns"   -> {
                int frames = (int)(imgDur * fps);
                if (frames < 1) frames = 1;
                yield ",zoompan=z='min(zoom+0.0015,1.3)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d="
                        + frames + ":s=" + w + "x" + h + ":fps=" + (int)fps;
            }
            default -> "";
        };

        return base + fx;
    }

    // ── Codec ─────────────────────────────────────────────────────────────────
    private void addCodec(List<String> cmd, String format, boolean hasAudio, double duration) {
        switch (format.toLowerCase()) {
            case "mp4" -> {
                cmd.add("-c:v");       cmd.add("libx264");
                cmd.add("-profile:v"); cmd.add("baseline");
                cmd.add("-level");     cmd.add("3.0");
                cmd.add("-preset");    cmd.add("fast");
                cmd.add("-crf");       cmd.add("23");
                cmd.add("-pix_fmt");   cmd.add("yuv420p");
                cmd.add("-movflags");  cmd.add("+faststart");
                if (hasAudio) {
                    cmd.add("-c:a");   cmd.add("aac");
                    cmd.add("-b:a");   cmd.add("192k");
                    cmd.add("-t");     cmd.add(String.format("%.3f", duration));
                } else {
                    cmd.add("-an");
                }
            }
            case "avi" -> {
                cmd.add("-c:v");     cmd.add("mpeg4");
                cmd.add("-pix_fmt"); cmd.add("yuv420p");
                if (hasAudio) { cmd.add("-c:a"); cmd.add("mp3"); }
                else cmd.add("-an");
            }
            case "webm" -> {
                cmd.add("-c:v"); cmd.add("libvpx-vp9");
                cmd.add("-b:v"); cmd.add("1M");
                if (hasAudio) { cmd.add("-c:a"); cmd.add("libopus"); }
                else cmd.add("-an");
            }
        }
    }

    private String getXfade(String t) {
        return switch (t == null ? "" : t.toLowerCase()) {
            case "crossfade"   -> "fade";
            case "slideleft"   -> "slideleft";
            case "slideright"  -> "slideright";
            case "slideup"     -> "slideup";
            case "slidedown"   -> "slidedown";
            case "zoomin"      -> "zoomin";
            case "flash"       -> "fadewhite";
            case "wipeleft"    -> "wipeleft";
            case "wiperight"   -> "wiperight";
            case "dissolve"    -> "dissolve";
            case "pixelize"    -> "pixelize";
            case "radial"      -> "radial";
            case "circleopen"  -> "circleopen";
            case "circleclose" -> "circleclose";
            default            -> "fade";
        };
    }

    private String getExt(String filename, String def) {
        if (filename == null || !filename.contains(".")) return def;
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder())
                 .map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {}
    }
}
