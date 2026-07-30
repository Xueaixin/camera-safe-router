package cn.camera.safe.validation;

import java.util.List;

public record SafetyValidationResult(List<RouteConflict> conflicts) {
    public SafetyValidationResult {
        conflicts = List.copyOf(conflicts);
    }

    public int conflictCount() {
        return conflicts.size();
    }

    public boolean compliant() {
        return conflicts.isEmpty();
    }
}
