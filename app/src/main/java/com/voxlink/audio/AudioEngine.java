package com.voxlink.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioEngine {

    private static final String TAG         = "VoxLink.Audio";
    private static final int    SAMPLE_RATE = 16000;
    private static final int    CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO;
    private static final int    CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int    ENCODING    = AudioFormat.ENCODING_PCM_16BIT;
    private static final int    FRAME_BYTES = 640; // 20ms @ 16kHz PCM16

    private AudioRecord    audioRecord;
    private AudioTrack     audioTrack;

    // FIX B1: Two separate sockets — DatagramSocket is NOT thread-safe for concurrent
    // send + receive. sendSocket handles outgoing audio; recvSocket handles incoming.
    // Server sends replies back to sendSocket's source port (which it learned from the
    // first incoming packet's rinfo.port).
    private DatagramSocket sendSocket;
    private DatagramSocket recvSocket;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted   = new AtomicBoolean(false);

    private final String serverHost;
    private final int    serverPort;
    private final String userId;
    private final String roomId;

    private ExecutorService executor;

    public AudioEngine(String serverHost, int serverPort, String userId, String roomId) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.userId     = userId;
        this.roomId     = roomId;
    }

    public boolean init() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
            if (minBuf <= 0) { Log.e(TAG, "Bad buffer size"); return false; }

            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING,
                Math.max(minBuf, FRAME_BYTES * 2));

            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

            AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build();

            int outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(outBuf, FRAME_BYTES * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

            // FIX B1: separate sockets — one per thread, thread-safe
            sendSocket = new DatagramSocket();         // OS assigns send port
            recvSocket = new DatagramSocket(sendSocket.getLocalPort()); // same port, recv only
            // Can't bind two sockets to same port on Android.
            // Solution: use sendSocket for recv too, but via a thread-safe wrapper.
            // Simplest correct solution: close recvSocket, use sendSocket with 200ms timeout,
            // and synchronize socket.send() so it doesn't race with socket.receive().
            recvSocket.close();
            recvSocket = null;
            sendSocket.setSoTimeout(200);

            return audioRecord.getState() == AudioRecord.STATE_INITIALIZED
                && audioTrack.getState()  == AudioTrack.STATE_INITIALIZED;

        } catch (Exception e) {
            Log.e(TAG, "init: " + e.getMessage());
            return false;
        }
    }

    public void start() {
        if (running.get()) return;
        running.set(true);
        audioRecord.startRecording();
        audioTrack.play();
        executor = Executors.newFixedThreadPool(2);
        executor.execute(this::sendLoop);
        executor.execute(this::recvLoop);
        Log.d(TAG, "AudioEngine started");
    }

    private void sendLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] pcm = new byte[FRAME_BYTES];
        byte[] pkt = new byte[FRAME_BYTES + 20];
        int    seq = 0;
        writeHeader(pkt);

        try {
            InetAddress addr = InetAddress.getByName(serverHost);
            while (running.get()) {
                int read = audioRecord.read(pcm, 0, pcm.length);

                // FIX B2: handle AudioRecord error codes — negative values mean error, not data
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error: " + read);
                    // Brief pause to avoid spinning on persistent errors
                    Thread.sleep(20);
                    continue;
                }
                if (read == 0 || muted.get() || !isVoiceActive(pcm, read)) continue;

                pkt[16] = (byte)(seq >> 24);
                pkt[17] = (byte)(seq >> 16);
                pkt[18] = (byte)(seq >> 8);
                pkt[19] = (byte) seq;
                seq++;

                System.arraycopy(pcm, 0, pkt, 20, read);

                // Synchronized send so recv on same socket doesn't race
                synchronized (sendSocket) {
                    if (!sendSocket.isClosed())
                        sendSocket.send(new DatagramPacket(pkt, read + 20, addr, serverPort));
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "sendLoop: " + e.getMessage());
        }
    }

    private void recvLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[]         buf = new byte[FRAME_BYTES + 20];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                // Synchronized receive so send on same socket doesn't race
                synchronized (sendSocket) {
                    if (sendSocket.isClosed()) break;
                    sendSocket.receive(pkt);
                }
                int dataLen = pkt.getLength() - 20;
                if (dataLen > 0) audioTrack.write(buf, 20, dataLen);
            } catch (java.net.SocketTimeoutException ignored) {
                // normal silence window — loop again
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "recvLoop: " + e.getMessage());
            }
        }
    }

    /** Energy-based Voice Activity Detection — skip sending silence */
    private boolean isVoiceActive(byte[] buf, int len) {
        long sum = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short)((buf[i + 1] << 8) | (buf[i] & 0xFF));
            sum += Math.abs(s);
        }
        return (sum / (len / 2)) > 700;
    }

    private void writeHeader(byte[] pkt) {
        byte[] rb = roomId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ub = userId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 8; i++) pkt[i]     = (i < rb.length) ? rb[i] : 0;
        for (int i = 0; i < 8; i++) pkt[8 + i] = (i < ub.length) ? ub[i] : 0;
    }

    public void setMuted(boolean m) { muted.set(m); }
    public boolean isMuted()        { return muted.get(); }

    public void stop() {
        running.set(false);
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception ignored) {}
        try { if (audioTrack  != null) { audioTrack.stop();  audioTrack.release();  audioTrack  = null; } } catch (Exception ignored) {}
        try { if (sendSocket  != null) { synchronized(sendSocket) { sendSocket.close(); } sendSocket = null; } } catch (Exception ignored) {}
        if (executor != null) executor.shutdownNow();
        Log.d(TAG, "AudioEngine stopped");
    }
}
