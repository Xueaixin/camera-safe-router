package cn.camera.safe.camera;

import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public final class CameraJsonLoader {
    private static final Set<String> ARRAY_WRAPPER_NAMES = Set.of(
            "data", "items", "result", "cameras", "records");
    private static final Pattern DIRECTION = Pattern.compile("\\[([^]\\r\\n]+)]");

    private final ObjectMapper objectMapper;
    private final CoordinateConverter coordinateConverter;

    public CameraJsonLoader(ObjectMapper objectMapper, CoordinateConverter coordinateConverter) {
        this.objectMapper = objectMapper;
        this.coordinateConverter = coordinateConverter;
    }

    public CameraLoadResult load(Path sourcePath) throws IOException {
        byte[] bytes = Files.readAllBytes(sourcePath.toAbsolutePath().normalize());
        JsonNode root = objectMapper.readTree(bytes);
        JsonNode records = locateRecords(root);
        Instant loadedAt = Instant.now();
        List<CameraPoint> cameras = new ArrayList<>(records.size());
        List<CameraValidationIssue> issues = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int retainedRecordCount = 0;
        int outsideSixRingRecordCount = 0;
        int unrecognizedSixRingOutRecordCount = 0;

        for (int index = 0; index < records.size(); index++) {
            JsonNode record = records.get(index);
            JsonNode isSixRingOut = record == null ? null : record.get("IsSixRingOut");
            if (isOutsideSixRing(isSixRingOut)) {
                outsideSixRingRecordCount++;
                continue;
            }

            retainedRecordCount++;
            if (!isInsideSixRing(isSixRingOut)) {
                unrecognizedSixRingOutRecordCount++;
            }
            String id = text(record, "Id");
            try {
                if (!record.isObject()) {
                    throw new IllegalArgumentException("record must be an object");
                }
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("Id is required");
                }
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("duplicate Id");
                }
                double lng = number(record, "Lng");
                double lat = number(record, "Lat");
                Gcj02Coordinate gcj02 = new Gcj02Coordinate(lng, lat);
                Wgs84Coordinate wgs84 = coordinateConverter.toWgs84(gcj02);
                String address = requiredText(record, "Address");
                cameras.add(new CameraPoint(
                        id,
                        nullableText(record, "District"),
                        gcj02,
                        wgs84,
                        address,
                        requiredText(record, "CameraType"),
                        extractDirection(address)));
            } catch (RuntimeException exception) {
                issues.add(new CameraValidationIssue(index, id, exception.getMessage()));
            }
        }

        return new CameraLoadResult(
                sha256(bytes),
                loadedAt,
                records.size(),
                retainedRecordCount,
                outsideSixRingRecordCount,
                unrecognizedSixRingOutRecordCount,
                cameras,
                issues);
    }

    private static boolean isOutsideSixRing(JsonNode value) {
        return hasFlagValue(value, BigDecimal.ONE);
    }

    private static boolean isInsideSixRing(JsonNode value) {
        return hasFlagValue(value, BigDecimal.ZERO);
    }

    private static boolean hasFlagValue(JsonNode value, BigDecimal expected) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return expected.toPlainString().equals(value.textValue());
        }
        return value.isNumber() && value.decimalValue().compareTo(expected) == 0;
    }

    private static JsonNode locateRecords(JsonNode root) {
        if (root == null) {
            throw new IllegalArgumentException("camera JSON is empty");
        }
        if (root.isArray()) {
            return root;
        }
        if (root.isObject()) {
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (ARRAY_WRAPPER_NAMES.contains(field.getKey().toLowerCase(Locale.ROOT))
                        && field.getValue().isArray()) {
                    return field.getValue();
                }
            }
        }
        throw new IllegalArgumentException("camera JSON must be an array or a supported object wrapper");
    }

    private static String requiredText(JsonNode node, String name) {
        String value = text(node, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String nullableText(JsonNode node, String name) {
        String value = text(node, name);
        return value == null ? "" : value;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static double number(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || (!value.isNumber() && !value.isTextual())) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
        try {
            double number = value.isNumber() ? value.doubleValue() : Double.parseDouble(value.textValue());
            if (!Double.isFinite(number)) {
                throw new NumberFormatException("not finite");
            }
            return number;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
    }

    private static String extractDirection(String address) {
        Matcher matcher = DIRECTION.matcher(address);
        String direction = null;
        while (matcher.find()) {
            direction = matcher.group(1).trim();
        }
        return direction;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
