package mcagent;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.List;

/** Listener notifications accepted during one request, in client observation order. */
final class EventJournal {
    private static final int CAPACITY = 32;

    record Entry(String event, JsonObject fields, List<InputQueue.Origin> listeners) {}
    record Report(List<Entry> entries, long omitted) {}

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long omitted;
    private boolean sealed;

    boolean isOpen() { return !sealed; }

    void record(String event, JsonObject fields, List<InputQueue.Origin> listeners) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
            omitted++;
        }
        entries.addLast(new Entry(event, fields == null ? null : fields.deepCopy(), List.copyOf(listeners)));
    }

    Report seal() {
        sealed = true;
        return new Report(List.copyOf(entries), omitted);
    }
}
