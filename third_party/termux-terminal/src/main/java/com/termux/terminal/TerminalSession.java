package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.UUID;

/**
 * A terminal session coupled to a remote byte stream instead of a local process.
 * <p>
 * MODIFIED for TrueNAS Companion (2026): the original Termux class spawned a local subprocess through JNI and a
 * pseudo-terminal. This version has no JNI and no process: bytes typed by the user go to a {@link Transport}
 * (the TrueNAS web shell WebSocket), bytes received from the server are handed to {@link #feed(byte[], int, int)},
 * and a size change is reported to {@link Transport#resize(int, int)}. Emulation and all callbacks still run on the
 * main thread, like the original.
 */
public final class TerminalSession extends TerminalOutput {

    /** Where the session's input goes. Implementations must not block the main thread. */
    public interface Transport {
        void send(byte[] data, int offset, int count);
        void resize(int columns, int rows);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_FINISHED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Written to from the network thread, read by the main thread and fed to the emulator. */
    final ByteQueue mRemoteToTerminalQueue = new ByteQueue(64 * 1024);
    /** Buffer to write translate code points into utf8 before sending them */
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;
    private final Transport mTransport;
    private volatile boolean mRunning = true;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;
    private final byte[] mReceiveBuffer = new byte[64 * 1024];

    public TerminalSession(Transport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        } else {
            mEmulator.resize(columns, rows);
        }
        mTransport.resize(columns, rows);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Bytes received from the remote side (any thread). */
    public void feed(byte[] data, int offset, int count) {
        if (!mRunning) return;
        int written = 0;
        while (written < count) {
            int chunk = Math.min(count - written, 16 * 1024);
            if (!mRemoteToTerminalQueue.write(data, offset + written, chunk)) return;
            written += chunk;
        }
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** The remote side ended (any thread). */
    public void markFinished() {
        mMainThreadHandler.sendEmptyMessage(MSG_FINISHED);
    }

    /** Write data to the remote side. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTransport.send(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        if (mEmulator != null) mEmulator.reset();
        notifyScreenUpdate();
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (mEmulator != null) {
                // Output that arrived before the view knew its size is kept in the queue until now.
                boolean changed = false;
                int bytesRead;
                while ((bytesRead = mRemoteToTerminalQueue.read(mReceiveBuffer, false)) > 0) {
                    mEmulator.append(mReceiveBuffer, bytesRead);
                    changed = true;
                }
                if (changed) notifyScreenUpdate();
            }
            if (msg.what == MSG_FINISHED && mRunning) {
                synchronized (TerminalSession.this) {
                    mRunning = false;
                }
                mRemoteToTerminalQueue.close();
                mClient.onSessionFinished(TerminalSession.this);
            }
        }
    }
}
