package app.wheelstop.android.server;

final class RecordingReconcilePolicy {
    private RecordingReconcilePolicy() {}

    static boolean shouldDeleteMissingRow(boolean rootAvailable,
                                          boolean scanComplete,
                                          boolean rowDiscovered) {
        return rootAvailable && scanComplete && !rowDiscovered;
    }
}