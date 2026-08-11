package cn.camera.safe.cli;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.routing.ControlledAreaBoundaryGenerator;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Command-line entry points for offline routing-data operations:
 *
 * <pre>
 * java -jar server.jar graph-build --pbf <file> --cache-out <dir> [--threads N]
 * java -jar server.jar graph-check --pbf <file> --cache <dir>
 * java -jar server.jar boundary-generate --sixth-ring-lines <file> --tongzhou <file>
 *        --pbf <file> --output <file> [--report <file>] [--approved]
 * </pre>
 */
public final class RoutingDataTool {

    private RoutingDataTool() {
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                usage();
                System.exit(2);
            }
            switch (args[0]) {
                case "graph-build" -> graphBuild(args);
                case "graph-check" -> graphCheck(args);
                case "boundary-generate" -> boundaryGenerate(args);
                case "help", "--help", "-h" -> usage();
                default -> {
                    System.err.println("unknown command: " + args[0]);
                    usage();
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("invalid arguments: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("command failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void graphCheck(String[] args) throws Exception {
        String pbf = value(args, "--pbf");
        String cache = value(args, "--cache");
        if (pbf == null || cache == null) {
            throw new IllegalArgumentException("graph-check requires --pbf and --cache");
        }
        Path pbfPath = Path.of(pbf).toAbsolutePath().normalize();
        Path cachePath = Path.of(cache).toAbsolutePath().normalize();
        requireFile(pbfPath, "pbf");
        if (!Files.isRegularFile(cachePath.resolve("properties"))) {
            throw new IllegalArgumentException("cache not found or invalid: " + cachePath);
        }
        AppProperties properties = new AppProperties(
                new AppProperties.Routing(
                        pbfPath.toString(),
                        cachePath.resolveSibling(cachePath.getFileName() + "-current-reference").toString(),
                        cachePath.toString(),
                        RoutingProfileMode.COMPLIANT_TIME_V2,
                        50,
                        2,
                        4,
                        Duration.ofSeconds(30),
                        2_000_000),
                defaultCameras(pbfPath),
                new AppProperties.Admin(true));
        GraphHopperManager manager = new GraphHopperManager(properties);
        manager.initialize();
        System.out.println("GRAPH_CHECK ok nodes=" + manager.requireHopper().getBaseGraph().getNodes()
                + " edges=" + manager.requireHopper().getBaseGraph().getEdges()
                + " fingerprint=" + manager.requireGraphFingerprint()
                + " sourceSha256=" + manager.requireSourcePbfSha256());
        manager.close();
    }

    private static void graphBuild(String[] args) throws Exception {
        String pbf = value(args, "--pbf");
        String cacheOut = value(args, "--cache-out");
        int threads = intValue(args, "--threads", 2);
        if (pbf == null || cacheOut == null) {
            throw new IllegalArgumentException("graph-build requires --pbf and --cache-out");
        }
        Path pbfPath = Path.of(pbf).toAbsolutePath().normalize();
        Path cachePath = Path.of(cacheOut).toAbsolutePath().normalize();
        if (!Files.isRegularFile(pbfPath)) {
            throw new IllegalArgumentException("PBF not found: " + pbfPath);
        }
        if (Files.exists(cachePath) && Files.list(cachePath).findAny().isPresent()) {
            throw new IllegalArgumentException(
                    "cache-out must be empty or missing to avoid overwriting an existing cache: "
                            + cachePath);
        }
        AppProperties properties = new AppProperties(
                new AppProperties.Routing(
                        pbfPath.toString(),
                        cachePath.resolveSibling(cachePath.getFileName() + "-current-reference").toString(),
                        cachePath.toString(),
                        RoutingProfileMode.COMPLIANT_TIME_V2,
                        50,
                        threads,
                        4,
                        Duration.ofSeconds(30),
                        2_000_000),
                defaultCameras(pbfPath),
                new AppProperties.Admin(true));
        System.out.println("GRAPH_BUILD pbf=" + pbfPath);
        System.out.println("GRAPH_BUILD cacheOut=" + cachePath);
        long started = System.nanoTime();
        GraphHopperManager manager = new GraphHopperManager(properties);
        manager.initialize();
        System.out.println("GRAPH_BUILD done nodes=" + manager.requireHopper().getBaseGraph().getNodes()
                + " edges=" + manager.requireHopper().getBaseGraph().getEdges()
                + " fingerprint=" + manager.requireGraphFingerprint()
                + " sourceSha256=" + manager.requireSourcePbfSha256()
                + " elapsedMillis=" + ((System.nanoTime() - started) / 1_000_000));
        manager.close();
    }

    private static void boundaryGenerate(String[] args) throws Exception {
        String lines = value(args, "--sixth-ring-lines");
        String tongzhou = value(args, "--tongzhou");
        String pbf = value(args, "--pbf");
        String output = value(args, "--output");
        String report = value(args, "--report");
        boolean approved = flag(args, "--approved");
        if (lines == null || tongzhou == null || pbf == null || output == null) {
            throw new IllegalArgumentException(
                    "boundary-generate requires --sixth-ring-lines, --tongzhou, --pbf and --output");
        }
        Path linesPath = Path.of(lines).toAbsolutePath().normalize();
        Path tongzhouPath = Path.of(tongzhou).toAbsolutePath().normalize();
        Path pbfPath = Path.of(pbf).toAbsolutePath().normalize();
        Path outputPath = Path.of(output).toAbsolutePath().normalize();
        requireFile(linesPath, "sixth-ring lines");
        requireFile(tongzhouPath, "tongzhou");
        requireFile(pbfPath, "pbf");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode linesRoot = objectMapper.readTree(linesPath.toFile());
        JsonNode tongzhouRoot = objectMapper.readTree(tongzhouPath.toFile());
        String pbfHash = Hashing.sha256(pbfPath).toLowerCase(java.util.Locale.ROOT);
        var controlledArea = ControlledAreaBoundaryGenerator.generateAndWrite(
                objectMapper,
                linesRoot,
                tongzhouRoot,
                outputPath,
                linesPath,
                tongzhouPath,
                pbfHash,
                approved);
        System.out.println("BOUNDARY_GENERATE output=" + outputPath
                + " approved=" + approved
                + " sourcePbfSha256=" + pbfHash
                + " " + ControlledAreaBoundaryGenerator.summarize(
                        controlledArea,
                        ControlledAreaBoundaryGenerator.sixthRingInsideFromLines(linesRoot),
                        ControlledAreaBoundaryGenerator.tongzhouPolygon(tongzhouRoot)));
        if (report != null && !report.isBlank()) {
            Path reportPath = Path.of(report).toAbsolutePath().normalize();
            Files.createDirectories(reportPath.getParent());
            Files.writeString(
                    reportPath,
                    "# 并集边界生成报告\n\n- 生成时间：" + java.time.Instant.now() + "\n"
                            + "- 源 PBF SHA-256：`" + pbfHash + "`\n"
                            + "- 六环线来源：`" + linesPath + "`\n"
                            + "- 通州来源：`" + tongzhouPath + "`\n"
                            + "- 候选边界：`" + outputPath + "`\n"
                            + "- 批准状态：" + approved + "\n");
            System.out.println("BOUNDARY_GENERATE report=" + reportPath);
        }
    }

    private static AppProperties.Cameras defaultCameras(Path pbfPath) {
        Path root = pbfPath.getParent() == null
                ? Path.of(".") : pbfPath.getParent().getParent();
        return new AppProperties.Cameras(
                root.resolve("cameras/camera.json").toString(),
                root.resolve("snapshots").toString(),
                true,
                10_000,
                new AppProperties.Update(
                        false,
                        "https://example.test/cameras",
                        "0 15 3 * * *",
                        "Asia/Shanghai",
                        root.resolve("downloads/cameras").toString(),
                        root.resolve("failed/cameras").toString(),
                        root.resolve("backups/cameras").toString(),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        10 * 1024 * 1024,
                        1000,
                        0.2,
                        0.98,
                        0.3,
                        30,
                        20,
                        20));
    }

    private static void requireFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " file not found: " + path);
        }
    }

    private static String value(String[] args, String key) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (key.equals(args[index])) {
                return args[index + 1];
            }
        }
        return null;
    }

    private static int intValue(String[] args, String key, int defaultValue) {
        String raw = value(args, key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw);
    }

    private static boolean flag(String[] args, String key) {
        for (String arg : args) {
            if (key.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void usage() {
        System.out.println("""
                Routing data tool
                  graph-build --pbf <file> --cache-out <dir> [--threads N]
                    Build the COMPLIANT_TIME_V2 graph cache from a PBF into an empty target
                    directory, writing camera-safe-source.sha256 and
                    camera-safe-routing-config.sha256 metadata.
                  graph-check --pbf <file> --cache <dir>
                    Load an existing cache and verify the PBF/configuration fingerprints
                    match (fails fast when they do not).
                  boundary-generate --sixth-ring-lines <file> --tongzhou <file> --pbf <file>
                    --output <file> [--report <file>] [--approved]
                    Generate the schema v3 controlled-area boundary from the exported
                    sixth-ring relation lines and Tongzhou relation polygon.
                """);
    }
}
