package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInputFile;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TollBoothSourceParser {

    TollBoothSourceData parse(Path sourcePbf) {
        Path normalized = sourcePbf.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalStateException("收费站走廊识别要求当前 PBF 文件存在: " + normalized);
        }

        Map<Long, MutableTollBooth> tollBooths = new HashMap<>();
        try (OSMInputFile input = new OSMInputFile(normalized.toFile())
                .setWorkerThreads(Math.max(1, Math.min(4,
                        Runtime.getRuntime().availableProcessors())))
                .open()) {
            ReaderElement element;
            while ((element = input.getNext()) != null) {
                if (element instanceof ReaderNode node
                        && node.hasTag("barrier", "toll_booth")) {
                    tollBooths.put(node.getId(), new MutableTollBooth(
                            node.getId(),
                            new Wgs84Coordinate(node.getLon(), node.getLat()),
                            node.getTag("name")));
                    continue;
                }
                if (!(element instanceof ReaderWay way) || tollBooths.isEmpty()) {
                    continue;
                }
                for (int index = 0; index < way.getNodes().size(); index++) {
                    MutableTollBooth tollBooth = tollBooths.get(way.getNodes().get(index));
                    if (tollBooth != null) {
                        tollBooth.adjacentWayIds.add(way.getId());
                    }
                }
            }
        } catch (IOException | XMLStreamException exception) {
            throw new IllegalStateException("无法解析 PBF 收费节点: " + normalized, exception);
        }

        List<TollBoothSourceData.TollBooth> result = new ArrayList<>(tollBooths.size());
        tollBooths.values().stream()
                .sorted(Comparator.comparingLong(value -> value.osmNodeId))
                .map(value -> new TollBoothSourceData.TollBooth(
                        value.osmNodeId,
                        value.coordinate,
                        value.name,
                        value.adjacentWayIds))
                .forEach(result::add);
        return new TollBoothSourceData(result);
    }

    private static final class MutableTollBooth {
        private final long osmNodeId;
        private final Wgs84Coordinate coordinate;
        private final String name;
        private final Set<Long> adjacentWayIds = new HashSet<>();

        private MutableTollBooth(
                long osmNodeId,
                Wgs84Coordinate coordinate,
                String name) {
            this.osmNodeId = osmNodeId;
            this.coordinate = coordinate;
            this.name = name;
        }
    }
}
