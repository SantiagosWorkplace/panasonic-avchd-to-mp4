import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta la Panasonic HC-V130 al conectarla por USB y, al tocar "Iniciar proceso",
 * convierte los clips AVCHD (.MTS) a MP4 1080p a 60 cuadros por segundo y copia las
 * fotos. Todo va a Escritorio\HANDYCAM\<fecha>\ (las fotos en la subcarpeta Fotos).
 * Lo que ya existe en el destino no se procesa de nuevo. No borra nada de la tarjeta.
 */
public class HandycamConverter {

    static final String CAMERA_MODEL = "HC-V130";
    static final String OUTPUT_FOLDER = "HANDYCAM";
    static final String PHOTOS_FOLDER = "Fotos";
    static final int POLL_MS = 3000;

    static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)");
    static final Pattern OUT_TIME = Pattern.compile("out_time_us=(\\d+)");

    static final Color GRAY = new Color(0x6B7280);
    static final Color BLUE = new Color(0x2563EB);
    static final Color ORANGE = new Color(0xD97706);
    static final Color GREEN = new Color(0x16A34A);
    static final Color RED = new Color(0xDC2626);

    final JFrame frame = new JFrame("HANDYCAM");
    final JLabel badge = new JLabel();
    final JLabel status = new JLabel("Conectá la cámara por USB.");
    final JProgressBar progress = new JProgressBar(0, 1000);
    final JTextArea log = new JTextArea(14, 60);
    final JButton startButton = new JButton("Iniciar proceso");

    final Path outputRoot = desktop().resolve(OUTPUT_FOLDER);
    String ffmpeg;
    List<String> videoEncoder;

    // Unidades ya revisadas (cámara o no) mientras siguen conectadas.
    final Set<String> seenDrives = new HashSet<>();
    volatile String cameraDrive;
    volatile boolean running;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new HandycamConverter().start());
    }

    void start() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        buildUi();
        setBadge("Esperando cámara", GRAY);

        ffmpeg = findFfmpeg();
        if (ffmpeg == null) {
            setBadge("Error", RED);
            status.setText("No se encontró ffmpeg.");
            log("ERROR: no se encontró ffmpeg.exe. Ponelo en la misma carpeta que este programa.");
            return;
        }
        log("Carpeta de salida: " + outputRoot);

        Thread watcher = new Thread(() -> {
            videoEncoder = pickEncoder(ffmpeg);
            log(videoEncoder.contains("h264_qsv")
                    ? "Conversión acelerada con la placa de video Intel (Quick Sync)."
                    : "Conversión por procesador (no hay Quick Sync disponible).");
            watchLoop();
        }, "watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    void buildUi() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        progress.setStringPainted(true);
        progress.setString("");

        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 13f));
        badge.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        JButton open = new JButton("Abrir carpeta HANDYCAM");
        open.addActionListener(e -> {
            try {
                Files.createDirectories(outputRoot);
                Desktop.getDesktop().open(outputRoot.toFile());
            } catch (IOException ex) {
                log("No se pudo abrir la carpeta: " + ex.getMessage());
            }
        });

        startButton.setEnabled(false);
        startButton.setFont(startButton.getFont().deriveFont(Font.BOLD));
        startButton.addActionListener(e -> {
            String drive = cameraDrive;
            if (drive == null || running) return;
            running = true;
            startButton.setEnabled(false);
            Thread worker = new Thread(() -> {
                try {
                    processCamera(new File(drive));
                } finally {
                    running = false;
                    SwingUtilities.invokeLater(() -> startButton.setEnabled(cameraDrive != null));
                }
            }, "worker");
            worker.setDaemon(true);
            worker.start();
        });

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 6));
        buttons.add(open);
        buttons.add(startButton);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));
        statusRow.add(badge);
        statusRow.add(status);

        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.add(statusRow, BorderLayout.NORTH);
        left.add(progress, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(12, 8));
        top.add(left, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(top, BorderLayout.NORTH);
        root.add(new JScrollPane(log), BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ---------------------------------------------------------------- detección

    void watchLoop() {
        while (true) {
            try {
                Set<String> present = new HashSet<>();
                for (File root : File.listRoots()) {
                    present.add(root.getPath());
                }
                seenDrives.retainAll(present);

                if (cameraDrive != null && !present.contains(cameraDrive)) {
                    log("Cámara desconectada.");
                    cameraDrive = null;
                    SwingUtilities.invokeLater(() -> startButton.setEnabled(false));
                    if (!running) {
                        setBadge("Esperando cámara", GRAY);
                        setStatus("Conectá la cámara por USB.");
                    }
                }

                for (String drive : present) {
                    if (seenDrives.contains(drive)) continue;
                    if (!new File(drive, "PRIVATE/AVCHD/BDMV").isDirectory()) continue;

                    seenDrives.add(drive);
                    if (!cameraDrives().contains(drive.substring(0, 2).toUpperCase())) {
                        log("La unidad " + drive + " tiene AVCHD pero no es una " + CAMERA_MODEL + ", se ignora.");
                        continue;
                    }
                    cameraDrive = drive;
                    log("Cámara " + CAMERA_MODEL + " detectada en " + drive + ". Tocá \"Iniciar proceso\".");
                    if (!running) {
                        setBadge("Cámara lista", BLUE);
                        setStatus("Cámara conectada en " + drive);
                        SwingUtilities.invokeLater(() -> startButton.setEnabled(true));
                    }
                }
            } catch (Exception e) {
                log("Error: " + e.getMessage());
            }
            sleep(POLL_MS);
        }
    }

    /** Letras de unidad (ej. "E:") que pertenecen a una HC-V130, según Windows. */
    static Set<String> cameraDrives() {
        String ps = "Get-CimInstance Win32_DiskDrive | Where-Object { $_.Model -match '" + CAMERA_MODEL + "' } | "
                + "Get-CimAssociatedInstance -ResultClassName Win32_DiskPartition | "
                + "Get-CimAssociatedInstance -ResultClassName Win32_LogicalDisk | "
                + "ForEach-Object { $_.DeviceID }";
        Set<String> drives = new HashSet<>();
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.matches("[A-Za-z]:")) drives.add(line.toUpperCase());
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
        }
        return drives;
    }

    // ----------------------------------------------------------------- proceso

    void processCamera(File drive) {
        setBadge("En proceso...", ORANGE);
        setProgress(0);

        List<File> clips = listFiles(new File(drive, "PRIVATE/AVCHD/BDMV/STREAM"), ".MTS");
        List<File> photos = new ArrayList<>();
        File[] dcim = new File(drive, "DCIM").listFiles(File::isDirectory);
        if (dcim != null) {
            for (File dir : dcim) {
                photos.addAll(listFiles(dir, ".JPG"));
                photos.addAll(listFiles(dir, ".JPEG"));
            }
        }

        Map<File, Path> newClips = new LinkedHashMap<>();
        for (File clip : clips) {
            Path target = targetFor(clip, false);
            if (!Files.exists(target)) newClips.put(clip, target);
        }
        Map<File, Path> newPhotos = new LinkedHashMap<>();
        for (File photo : photos) {
            Path target = targetFor(photo, true);
            if (!Files.exists(target)) newPhotos.put(photo, target);
        }
        log(clips.size() + " videos (" + newClips.size() + " nuevos) y "
                + photos.size() + " fotos (" + newPhotos.size() + " nuevas) en la cámara.");

        int failed = 0;
        int i = 0;
        for (Map.Entry<File, Path> e : newPhotos.entrySet()) {
            i++;
            setStatus("Copiando foto " + i + " de " + newPhotos.size());
            setProgress(i * 1000 / newPhotos.size());
            if (!copyPhoto(e.getKey(), e.getValue())) failed++;
        }
        i = 0;
        for (Map.Entry<File, Path> e : newClips.entrySet()) {
            i++;
            setStatus("Convirtiendo video " + i + " de " + newClips.size() + ": " + e.getKey().getName());
            setProgress(0);
            if (!convert(e.getKey(), e.getValue())) failed++;
        }

        int done = newClips.size() + newPhotos.size() - failed;
        setProgress(done + failed == 0 ? -1 : 1000);
        String summary = newClips.isEmpty() && newPhotos.isEmpty()
                ? "No había nada nuevo. Podés desconectar la cámara."
                : done + " archivos listos" + (failed > 0 ? ", " + failed + " con error" : "")
                  + ". Podés desconectar la cámara.";
        if (failed > 0) setBadge("Finalizado con errores", RED);
        else setBadge("Finalizado", GREEN);
        setStatus(summary);
        log(summary);
    }

    static List<File> listFiles(File dir, String ext) {
        File[] files = dir.listFiles((d, n) -> n.toUpperCase().endsWith(ext));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }

    /** Destino según la fecha del archivo: <fecha>\<fecha_hora>_<nombre>.mp4, o <fecha>\Fotos\... */
    Path targetFor(File source, boolean photo) {
        Date date = new Date(source.lastModified());
        String day = new SimpleDateFormat("yyyy-MM-dd").format(date);
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(date);
        Path dir = outputRoot.resolve(day);
        if (photo) return dir.resolve(PHOTOS_FOLDER).resolve(stamp + "_" + source.getName());
        String base = source.getName().replaceFirst("(?i)\\.mts$", "");
        return dir.resolve(stamp + "_" + base + ".mp4");
    }

    boolean copyPhoto(File photo, Path target) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(photo.toPath(), part, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            log("OK  " + photo.getName() + "  ->  " + outputRoot.relativize(target));
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            log("ERROR al copiar " + photo.getName() + ": " + e.getMessage());
            return false;
        }
    }

    boolean convert(File clip, Path target) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    ffmpeg, "-hide_banner", "-y", "-nostdin",
                    "-i", clip.getAbsolutePath(),
                    // Desentrelazado: cada campo del 60i pasa a ser un cuadro completo (60p).
                    "-vf", "bwdif=mode=send_field:parity=auto:deint=all,scale=1920:1080,setsar=1"));
            cmd.addAll(videoEncoder);
            cmd.addAll(Arrays.asList(
                    "-c:a", "aac", "-b:a", "192k",
                    "-movflags", "+faststart",
                    "-progress", "pipe:1", "-nostats",
                    "-f", "mp4", part.toString()));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            double durationUs = 0;
            List<String> tail = new LinkedList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    tail.add(line);
                    if (tail.size() > 15) tail.remove(0);
                    Matcher d = DURATION.matcher(line);
                    if (durationUs == 0 && d.find()) {
                        durationUs = (Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60
                                + Double.parseDouble(d.group(3))) * 1_000_000;
                    }
                    Matcher t = OUT_TIME.matcher(line);
                    if (durationUs > 0 && t.find()) {
                        setProgress((int) Math.min(1000, Long.parseLong(t.group(1)) * 1000 / durationUs));
                    }
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                Files.deleteIfExists(part);
                log("ERROR al convertir " + clip.getName() + ":");
                tail.forEach(l -> log("   " + l));
                return false;
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            log("OK  " + clip.getName() + "  ->  " + outputRoot.relativize(target));
            return true;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            log("ERROR al convertir " + clip.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /** Usa Intel Quick Sync si la PC lo tiene (mucho más rápido); si no, el procesador. */
    static List<String> pickEncoder(String ffmpeg) {
        List<String> qsv = Arrays.asList("-c:v", "h264_qsv", "-preset", "slower", "-global_quality", "18",
                "-profile:v", "high", "-pix_fmt", "nv12");
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
                    "-f", "lavfi", "-i", "color=black:s=1920x1080:d=0.2"));
            cmd.addAll(qsv);
            cmd.addAll(Arrays.asList("-f", "null", "-"));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) return qsv;
        } catch (Exception ignored) {
        }
        return Arrays.asList("-c:v", "libx264", "-preset", "faster", "-crf", "18",
                "-profile:v", "high", "-pix_fmt", "yuv420p");
    }

    // ------------------------------------------------------------------ helpers

    /** Busca ffmpeg.exe junto al programa y, si no, en el PATH. */
    static String findFfmpeg() {
        List<File> dirs = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) dirs.add(new File(appPath).getParentFile());
        try {
            File jar = new File(HandycamConverter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            dirs.add(jar.getParentFile());
            if (jar.getParentFile() != null) dirs.add(jar.getParentFile().getParentFile());
        } catch (Exception ignored) {
        }
        for (File dir : dirs) {
            if (dir == null) continue;
            File f = new File(dir, "ffmpeg.exe");
            if (f.isFile()) return f.getAbsolutePath();
        }
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) return "ffmpeg";
        } catch (Exception ignored) {
        }
        return null;
    }

    static Path desktop() {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "[Environment]::GetFolderPath('Desktop')").start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!out.isEmpty() && Files.isDirectory(Paths.get(out))) return Paths.get(out);
        } catch (Exception ignored) {
        }
        return Paths.get(System.getProperty("user.home"), "Desktop");
    }

    void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + msg + "\n";
        SwingUtilities.invokeLater(() -> {
            log.append(line);
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> status.setText(msg));
    }

    void setBadge(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            badge.setText(text);
            badge.setBackground(color);
        });
    }

    /** permil de 0 a 1000; -1 limpia la barra. */
    void setProgress(int permil) {
        SwingUtilities.invokeLater(() -> {
            progress.setValue(Math.max(permil, 0));
            progress.setString(permil < 0 ? "" : (permil / 10) + "%");
        });
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
