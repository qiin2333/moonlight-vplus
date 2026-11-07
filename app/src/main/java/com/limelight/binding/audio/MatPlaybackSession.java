package com.limelight.binding.audio;

/** Shared live-session lifecycle, independently testable without Android hardware. */
public final class MatPlaybackSession implements MatCarrierConsumer, AutoCloseable {
    private final MatCarrierPlayer player;
    public MatPlaybackSession(MatCarrierPlayer.Sink sink) throws InterruptedException {
        player = new MatCarrierPlayer(sink);
        player.start();
        try {
            if (!player.awaitReady(3000)) throw new IllegalStateException("MAT sink not ready: " + player.error());
        } catch (InterruptedException | RuntimeException e) { player.close(); throw e; }
    }
    @Override public boolean offerMatCycle(byte[] bytes,int length,long epoch,long domain,long index,long pts48k) {
        return player.offerMatCycle(bytes,length,epoch,domain,index,pts48k);
    }
    @Override public boolean endMatStream(long cycles) {
        if (!player.endMatStream(cycles)) return false;
        try { return player.awaitCompletion(5000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    public MatCarrierPlayer player() { return player; }
    @Override public void close() { player.close(); }
}
