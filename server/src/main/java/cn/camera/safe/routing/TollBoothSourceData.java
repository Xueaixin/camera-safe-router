package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

record TollBoothSourceData(List<TollBooth> tollBooths) {
    TollBoothSourceData {
        tollBooths = List.copyOf(Objects.requireNonNull(tollBooths, "tollBooths"));
    }

    record TollBooth(
            long osmNodeId,
            Wgs84Coordinate coordinate,
            String name,
            Set<Long> adjacentWayIds) {
        TollBooth {
            Objects.requireNonNull(coordinate, "coordinate");
            name = name == null || name.isBlank() ? null : name.trim();
            adjacentWayIds = Set.copyOf(Objects.requireNonNull(
                    adjacentWayIds, "adjacentWayIds"));
        }
    }
}
