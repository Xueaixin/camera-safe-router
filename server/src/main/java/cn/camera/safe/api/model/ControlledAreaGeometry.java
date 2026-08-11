package cn.camera.safe.api.model;

import java.util.List;

public record ControlledAreaGeometry(
        String type,
        List<List<List<OutputCoordinate>>> coordinates) {

    public ControlledAreaGeometry {
        if (!"MultiPolygon".equals(type)) {
            throw new IllegalArgumentException("controlled area geometry must be MultiPolygon");
        }
        coordinates = coordinates.stream()
                .map(polygon -> polygon.stream().map(List::copyOf).toList())
                .toList();
        if (coordinates.isEmpty()) {
            throw new IllegalArgumentException("controlled area geometry must not be empty");
        }
    }
}
