package cn.camera.safe.routing;

import com.graphhopper.routing.ev.DefaultImportRegistry;
import com.graphhopper.routing.ev.ImportRegistry;
import com.graphhopper.routing.ev.ImportUnit;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;

final class RoadIdentityImportRegistry implements ImportRegistry {
    private final DefaultImportRegistry delegate = new DefaultImportRegistry();

    @Override
    public ImportUnit createImportUnit(String name) {
        if (RoadIdentityEncodedValues.SIXTH_RING_MAINLINE.equals(name)) {
            return ImportUnit.create(
                    name,
                    ignored -> new SimpleBooleanEncodedValue(name),
                    null);
        }
        return delegate.createImportUnit(name);
    }
}
