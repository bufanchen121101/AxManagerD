package frb.axeron.api.utils;

import java.util.List;
import java.util.Set;

public record Excluder(Mode mode, Set<String> excludedExt, boolean caseSensitive) {

    public static boolean shouldSmartExclude(String relativePath, List<Excluder> excluders) {
        if (excluders == null || excluders.isEmpty()) return false;

        for (Excluder excluder : excluders) {
            if (excluder.excludedExt == null || excluder.excludedExt.isEmpty()) continue;

            // Pilih nama sesuai mode caseSensitive
            String compareName = excluder.caseSensitive ? relativePath : relativePath.toLowerCase();

            for (String ext : excluder.excludedExt) {
                String compareExt = excluder.caseSensitive ? ext : ext.toLowerCase();

                switch (excluder.mode) {
                    case START:
                        if (compareName.startsWith(compareExt)) return true;
                        break;
                    case END:
                        if (compareName.endsWith(compareExt)) return true;
                        break;
                    case EQUALS:
                        if (compareName.equals(compareExt)) return true;
                        break;
                    case CONTAINS:
                        if (compareName.contains(compareExt)) return true;
                        break;
                    case MATCHES:
                        if (compareName.matches(compareExt)) return true;
                        break;
                }

            }
        }

        return false;
    }

    public enum Mode {
        START,
        END,
        EQUALS,
        CONTAINS,
        MATCHES
    }
}
